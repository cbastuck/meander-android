// JNI shim that starts/stops the embedded hkp-rt runtime for the Android app.
//
// This is the Android analogue of iOS's HKPRuntimeHost.mm: it owns a single
// process-wide hkp::App + hkp::Server on a background thread, binds 0.0.0.0
// (LAN) or 127.0.0.1 (local-only) based on the caller's preference, runs in Jwt
// auth mode with the Auth0 issuer and a fail-closed allow-list, and reports the
// bound port back to Kotlin (RuntimeHost.runtimeBaseUrl = http://127.0.0.1:<port>).
//
// It also registers LAN-discovery platform hooks that acquire/release a
// WifiManager multicast lock around each discover window (Android drops
// multicast otherwise). The hooks upcall into the Kotlin controller object
// passed to nativeStart().

#include <jni.h>
#include <android/log.h>

#include <app.h>
#include <auth.h>
#include <discovery_hooks.h>
#include <server.h>

#include <chrono>
#include <future>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#define HKP_LOG_TAG "hkp-rt"
#define HKP_LOGI(...) __android_log_print(ANDROID_LOG_INFO, HKP_LOG_TAG, __VA_ARGS__)
#define HKP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, HKP_LOG_TAG, __VA_ARGS__)

namespace
{

// The runtime is a single process-wide instance (like iOS's shared host). All
// access is serialized through g_mutex.
std::mutex g_mutex;
std::shared_ptr<hkp::App> g_app;
std::shared_ptr<hkp::Server> g_server;
std::shared_ptr<std::thread> g_serverThread;

// Multicast-lock upcall plumbing. g_multicastController is a global ref to the
// Kotlin object exposing acquireMulticastLock()/releaseMulticastLock(); the
// discover thread attaches to the JVM to call them.
JavaVM* g_vm = nullptr;
jobject g_multicastController = nullptr;
jmethodID g_acquireMethod = nullptr;
jmethodID g_releaseMethod = nullptr;

std::string jstringToStd(JNIEnv* env, jstring value)
{
  if (value == nullptr)
  {
    return {};
  }
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string result(chars != nullptr ? chars : "");
  if (chars != nullptr)
  {
    env->ReleaseStringUTFChars(value, chars);
  }
  return result;
}

// Invokes a no-arg void method on the multicast controller from any thread,
// attaching to the JVM if the caller is a native (discover) thread.
void callMulticastMethod(jmethodID method)
{
  if (g_vm == nullptr || g_multicastController == nullptr || method == nullptr)
  {
    return;
  }

  JNIEnv* env = nullptr;
  bool attached = false;
  const jint rc = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
  if (rc == JNI_EDETACHED)
  {
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK)
    {
      return;
    }
    attached = true;
  }
  else if (rc != JNI_OK)
  {
    return;
  }

  env->CallVoidMethod(g_multicastController, method);
  if (env->ExceptionCheck())
  {
    env->ExceptionClear();
  }

  if (attached)
  {
    g_vm->DetachCurrentThread();
  }
}

// Tears down the server + thread. Caller must hold g_mutex.
void stopLocked()
{
  if (g_server)
  {
    g_server->stop();
  }
  if (g_serverThread && g_serverThread->joinable())
  {
    g_serverThread->join();
  }
  g_serverThread.reset();
  g_server.reset();
  g_app.reset();
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/)
{
  g_vm = vm;
  return JNI_VERSION_1_6;
}

// Starts the runtime (idempotent: returns the live port if already running).
// Returns the bound HTTP port, or -1 on failure.
JNIEXPORT jint JNICALL
Java_com_readymadeit_meander_runtime_RuntimeHost_nativeStart(
    JNIEnv* env,
    jobject /*thiz*/,
    jint port,
    jstring allowedOrigins,
    jstring deviceName,
    jstring bindAddress,
    jobject multicastController)
{
  std::lock_guard<std::mutex> lock(g_mutex);

  if (g_server)
  {
    return static_cast<jint>(g_server->port());
  }

  const std::string origins = jstringToStd(env, allowedOrigins);
  const std::string displayName = jstringToStd(env, deviceName);
  std::string bind = jstringToStd(env, bindAddress);
  if (bind.empty())
  {
    bind = "127.0.0.1";
  }

  // Cache a global ref + method ids for the multicast-lock upcalls.
  if (g_multicastController != nullptr)
  {
    env->DeleteGlobalRef(g_multicastController);
    g_multicastController = nullptr;
    g_acquireMethod = nullptr;
    g_releaseMethod = nullptr;
  }
  if (multicastController != nullptr)
  {
    g_multicastController = env->NewGlobalRef(multicastController);
    jclass cls = env->GetObjectClass(multicastController);
    g_acquireMethod = env->GetMethodID(cls, "acquireMulticastLock", "()V");
    g_releaseMethod = env->GetMethodID(cls, "releaseMulticastLock", "()V");
    env->DeleteLocalRef(cls);
  }

  // Register discovery hooks: hold the multicast lock while a window is active.
  hkp::DiscoveryPlatformHooks hooks;
  hooks.onWindowStart = []() { callMulticastMethod(g_acquireMethod); };
  hooks.onWindowStop = []() { callMulticastMethod(g_releaseMethod); };
  hkp::setDiscoveryPlatformHooks(std::move(hooks));

  try
  {
    auto app = std::make_shared<hkp::App>();
    // v1 registers no native services; all portable built-in services come from
    // the Registry. Native sensor services (Motion/Location/Mic/Speaker) are
    // added here later, one registerService<T>() call each, as on iOS.

    // Exposed runtimes must authenticate inbound peers: Jwt mode, Auth0 issuer,
    // fail-closed allow-list (empty until a signed-in / manual user is admitted).
    // The local UI always works through the loopback bypass.
    hkp::AuthConfig authConfig;
    authConfig.mode = hkp::AuthMode::Jwt;
    hkp::TrustedIssuer issuer;
    issuer.iss = "https://hookitapp.eu.auth0.com/";
    // Both Auth0 applications: the token's `aud` is the client id that signed
    // the caller in, and a browser reaching this runtime over the LAN carries
    // the website's, not the app's.
    issuer.audiences = {"gpk8IFPKfaOTQUzpDRO7vBajOnB72rkM",   // Native — the apps
                        "x4iF0MBYfd25oLdJbATNe03dCDUtBs74"};  // SPA — website
    authConfig.issuers.push_back(issuer);

    auto server = std::make_shared<hkp::Server>(
        app, "meander-android", origins, displayName, authConfig);

    // start() blocks for the server's lifetime; use a promise to detect an
    // *early* failure (bind error) within a 500 ms window, exactly like iOS.
    auto startPromise = std::make_shared<std::promise<void>>();
    std::future<void> startFuture = startPromise->get_future();
    const unsigned int usePort = static_cast<unsigned int>(port);

    auto thread = std::make_shared<std::thread>(
        [server, usePort, bind, startPromise]() mutable {
          try
          {
            server->start("127.0.0.1", usePort, bind);
          }
          catch (...)
          {
            try
            {
              startPromise->set_exception(std::current_exception());
            }
            catch (const std::future_error&)
            {
            }
          }
        });

    const auto status = startFuture.wait_for(std::chrono::milliseconds(500));
    if (status == std::future_status::ready)
    {
      startFuture.get();  // re-throws the bind/startup exception
    }

    g_app = app;
    g_server = server;
    g_serverThread = thread;

    const unsigned int actualPort = server->port();
    HKP_LOGI("runtime started on port %u (bind %s)", actualPort, bind.c_str());
    return static_cast<jint>(actualPort);
  }
  catch (const std::exception& e)
  {
    HKP_LOGE("failed to start runtime: %s", e.what());
    stopLocked();
    return -1;
  }
  catch (...)
  {
    HKP_LOGE("failed to start runtime: unknown error");
    stopLocked();
    return -1;
  }
}

JNIEXPORT void JNICALL
Java_com_readymadeit_meander_runtime_RuntimeHost_nativeStop(JNIEnv* env, jobject /*thiz*/)
{
  std::lock_guard<std::mutex> lock(g_mutex);
  stopLocked();
  if (g_multicastController != nullptr)
  {
    env->DeleteGlobalRef(g_multicastController);
    g_multicastController = nullptr;
    g_acquireMethod = nullptr;
    g_releaseMethod = nullptr;
  }
}

JNIEXPORT jint JNICALL
Java_com_readymadeit_meander_runtime_RuntimeHost_nativePort(JNIEnv* /*env*/, jobject /*thiz*/)
{
  std::lock_guard<std::mutex> lock(g_mutex);
  return g_server ? static_cast<jint>(g_server->port()) : -1;
}

JNIEXPORT void JNICALL
Java_com_readymadeit_meander_runtime_RuntimeHost_nativeSetAllowedUsers(
    JNIEnv* env, jobject /*thiz*/, jobjectArray emails)
{
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_server)
  {
    return;
  }
  std::vector<std::string> allowed;
  if (emails != nullptr)
  {
    const jsize count = env->GetArrayLength(emails);
    allowed.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i)
    {
      auto element = static_cast<jstring>(env->GetObjectArrayElement(emails, i));
      std::string value = jstringToStd(env, element);
      if (!value.empty())
      {
        allowed.push_back(std::move(value));
      }
      if (element != nullptr)
      {
        env->DeleteLocalRef(element);
      }
    }
  }
  g_server->setAllowedUsers(allowed);
}

}  // extern "C"
