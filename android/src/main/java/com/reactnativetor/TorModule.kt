package com.reactnativetor

import android.os.AsyncTask
import android.util.Log
import com.facebook.react.bridge.*
import com.sifir.tor.DataObserver
import com.sifir.tor.OwnedTorService
import com.sifir.tor.TcpSocksStream
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.net.Proxy;
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter

/**
 * Wraps DataObserver interface into event emitter
 * Sent across FFI and will emit on data based on target-data or target-error topic
 */
class DataObserverEmitter(target:String, reactContext: ReactApplicationContext) : DataObserver{
  private val target = target;
  private val context = reactContext;
  override fun onData(p0: String?) {
    context
      .getJSModule(RCTDeviceEventEmitter::class.java)
      .emit("$target-data", p0)
  }
  override fun onError(p0: String?) {
    context
      .getJSModule(RCTDeviceEventEmitter::class.java)
      .emit("$target-error", p0)
  }
}
class TorModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
  private var service: OwnedTorService? = null;
  private var proxy: Proxy? = null;
  private var _starting: Boolean = false;
  private var _streams:HashMap<String,TcpSocksStream> = HashMap();

  /**
   * Gets a client that accepts all SSL certs
   * TODO Not sure how i feel about this but seems to be the only way
   * to accept self signed certs for onion urls
   * if anyone knows of a better way please PR this.
   */
  private fun getUnsafeOkHttpClient(): OkHttpClient.Builder {
    // Create a trust manager that does not validate certificate chains
    val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
      override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
      }

      override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
      }

      override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    })

    // Install the all-trusting trust manager
    val sslContext = SSLContext.getInstance("SSL")
    sslContext.init(null, trustAllCerts, java.security.SecureRandom())
    // Create an ssl socket factory with our all-trusting manager
    val sslSocketFactory = sslContext.socketFactory

    return OkHttpClient.Builder()
      .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
      .hostnameVerifier { _, _ -> true }
  }

  override fun getName(): String {
    return "TorBridge"
  }

  private fun findFreePort(): Int {
    var socket: ServerSocket? = null
    try {
      socket = ServerSocket(0)
      socket.reuseAddress = true
      val port = socket.localPort
      try {
        socket.close()
      } catch (e: IOException) {
        // Ignore IOException on close()
      }
      return port
    } catch (e: IOException) {
    } finally {
      if (socket != null) {
        try {
          socket.close()
        } catch (e: IOException) {
        }
      }
    }
    throw Throwable("Could not find a free TCP/IP port for Socks Proxy")
  }

  @ReactMethod
  fun request(
    url: String,
    method: String,
    jsonBody: String,
    headers: ReadableMap,
    trustAllSSl: Boolean,
    promise: Promise
  ) {
    if (service == null) {
      promise.reject(Throwable("Service Not Initialized!, Call startDaemon first"));
    }

    var client = (if (trustAllSSl) getUnsafeOkHttpClient() else OkHttpClient().newBuilder())
      .proxy(proxy)
      .connectTimeout(10, TimeUnit.SECONDS)
      .writeTimeout(10, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.SECONDS)
      .build()

    try {
      val param = TaskParam(method, url, jsonBody, headers.toHashMap())
      TorBridgeAsyncTask(promise, client).executeOnExecutor(
        AsyncTask.THREAD_POOL_EXECUTOR, param
      )
    } catch (e: Exception) {
      Log.d("TorBridge", "error on sendRequest$e")
      promise.reject(e)
    }
  }


  @ReactMethod
  fun startDaemon(promise: Promise) {
    if (service != null) {
      promise.reject(Throwable("Service already running, call stopDaemon first"))
    }
    if (this._starting) {
      promise.reject(Throwable("Service already starting"))
    }
    _starting = true;
    try {
      val socksPort = findFreePort();
      val path = this.reactApplicationContext.cacheDir.toString();
      val param = StartParam(socksPort, path)
      TorBridgeStartAsync({
        service = it
        proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("0.0.0.0", socksPort))
        _starting = false;
        promise.resolve(socksPort);
      }, {
        _starting = false;
        promise.reject(it);
      }).executeOnExecutor(
        AsyncTask.THREAD_POOL_EXECUTOR, param
      )
    } catch (e: Exception) {
      Log.d("TorBridge", "error on sendRequest $e")
      promise.reject(e)
    }
  }

  @ReactMethod
  fun getDaemonStatus(promise: Promise) {
    if (_starting) {
      promise.resolve("STARTING");
      return;
    }
    if (service == null) {
      promise.resolve("NOTINIT");
      return;
    }

    val status = service?.get_status();
    promise.resolve(status);
  }

  @ReactMethod
  fun stopDaemon(promise: Promise) {
    try {
      service?.shutdown();
      service = null
      proxy = null;
      promise.resolve(true);
    } catch (e: Exception) {
      Log.d("TorBridge", "error on stopDaemon$e")
      promise.reject(e)
    }
  }

  @ReactMethod
  fun startTcpConn(target:String,promise:Promise) {
    try {
      if (service == null) {
        throw Throwable("Tor service not running, call startDaemon first")
      }
      // FIXME check if stream already exists and remove it
      val stream = TcpSocksStream(target,"0.0.0.0:"+service?.socksPort);
      stream.on_data(DataObserverEmitter(target,this.reactApplicationContext));
      _streams.set(target,stream);
      promise.resolve(true);
    } catch (e: Exception) {
      Log.d("TorBridge", "error on startTcpConn$e")
      promise.reject(e)
    }
  }

  @ReactMethod
  fun sendTcpConnMsg(target:String,msg:String,timeoutSec:Double,promise:Promise) {
    try {
      if (service == null) {
        throw Throwable("Tor Service not running, call startDaemon first")
      }
      var stream = _streams.get(target);
      if(stream == null){
        throw Throwable("Stream for target is not initialized, call startTcpConn first");
      }
      stream?.send_data(msg,timeoutSec.toLong());
      promise.resolve(true);
    } catch (e: Exception) {
      Log.d("TorBridge", "error on sendTcpConnMsg$e")
      promise.reject(e)
    }
  }

  @ReactMethod
  fun stopTcpConn(target:String,promise:Promise) {
    try {
     _streams.remove(target);
      promise.resolve(true);
    } catch (e: Exception) {
      Log.d("TorBridge", "error on stopTcpConn$e")
      promise.reject(e)
    }
  }
}
