package com.deviceagent.app;

import android.app.Activity;
import android.content.Context;
import android.os.CancellationSignal;
import androidx.credentials.*;
import androidx.credentials.exceptions.*;
import com.deviceagent.rpc.SimpleHttp;
import com.deviceagent.tools.Json;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Native passkey UI; enrollment codes stay between the app and the server. */
final class PhoneSignIn {
    interface Callback { void ready(RelayLinker.Result result); void failed(String message); }
    private static final String BASE = SetupRules.DEFAULT_RELAY + "/work/native";
    private static android.content.SharedPreferences preferences(Context c) {return c.getSharedPreferences("backstage-sign-in",Context.MODE_PRIVATE);}
    static boolean pending(Context c) {return preferences(c).contains("ticket");}
    static void resume(Context c,Callback callback) {enroll(c.getApplicationContext(),callback);}
    private static Map<String,Object> post(String path, Map<String,Object> body) throws Exception {
        return Json.object(SimpleHttp.requestNoRedirect("POST", BASE + path, Json.stringify(body), 25000, null));
    }
    /** A per-install random ID; the server uses it to resume an interrupted sign-in. */
    private static String installation(Context c) {
        String installation=preferences(c).getString("installation",null);
        if(installation==null){byte[] random=new byte[32];new java.security.SecureRandom().nextBytes(random);installation=android.util.Base64.encodeToString(random,android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING);preferences(c).edit().putString("installation",installation).commit();}
        return installation;
    }
    /** The name this device gets on the account: the phone's own name, else its model. */
    static String deviceName(Context c) {
        String name=null;
        try{name=android.provider.Settings.Global.getString(c.getContentResolver(),"device_name");}catch(RuntimeException ignored){}
        if(name==null||name.isBlank())name=android.os.Build.MODEL;
        name=name.replaceAll("[^A-Za-z0-9 ._-]","").trim().replaceAll("^[^A-Za-z0-9]+","");
        if(name.length()>32)name=name.substring(0,32).trim();
        return name.isEmpty()?"phone":name;
    }
    interface Sent { void sent(); void failed(String message); }
    /** Create account step 1: submit email + password and request a 6-digit verification OTP. */
    static void signUpStart(Context context, String email, String password, Sent callback) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try { post("/signup/start", Json.mapOf("email", email, "password", password, "installation", installation(app))); callback.sent(); }
            catch (SimpleHttp.HttpException e) { callback.failed(message(e, "Could not start sign-up. Try again.")); }
            catch (Exception e) { callback.failed("Could not reach Backstage. Check your internet connection and try again."); }
        }, "backstage-signup").start();
    }
    /** Create account step 2: verify the 6-digit OTP and enroll this phone. */
    static void signUpVerify(Context context, String email, String code, Callback callback) {
        verified(context, "/signup/verify", Json.mapOf("email", email, "code", code), callback);
    }
    /** Standard email + password login. */
    static void passwordLogin(Context context, String email, String password, Callback callback) {
        verified(context, "/login", Json.mapOf("email", email, "password", password), callback);
    }
    /** Forgot password step 1: request a 6-digit reset OTP. */
    static void passwordResetStart(Context context, String email, Sent callback) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try { post("/password/reset/start", Json.mapOf("email", email, "installation", installation(app))); callback.sent(); }
            catch (SimpleHttp.HttpException e) { callback.failed(message(e, "Could not send a reset code. Try again.")); }
            catch (Exception e) { callback.failed("Could not reach Backstage. Check your internet connection and try again."); }
        }, "backstage-reset").start();
    }
    /** Forgot password step 2: verify reset OTP, set new password, and enroll this phone. */
    static void passwordResetVerify(Context context, String email, String code, String newPassword, Callback callback) {
        verified(context, "/password/reset/verify", Json.mapOf("email", email, "code", code, "password", newPassword), callback);
    }
    /** Email a sign-in code. A new address creates an account. */
    static void emailStart(Context context,String email,Sent callback) {
        Context app=context.getApplicationContext();
        new Thread(() -> {
            try{post("/email/start",Json.mapOf("email",email,"installation",installation(app)));callback.sent();}
            catch(SimpleHttp.HttpException e){callback.failed(message(e,"The code could not be sent. Try again."));}
            catch(Exception e){callback.failed("Could not reach Backstage. Check your internet connection and try again.");}
        },"backstage-email").start();
    }
    static void emailVerify(Context context,String email,String code,Callback callback) {
        verified(context,"/email/verify",Json.mapOf("email",email,"code",code),callback);
    }
    static void recovery(Context context,String code,Callback callback) {
        verified(context,"/recovery",Json.mapOf("code",code),callback);
    }
    private static void verified(Context context,String path,Map<String,Object> body,Callback callback) {
        Context app=context.getApplicationContext();
        new Thread(() -> {
            try {
                body.put("installation",installation(app));body.put("deviceName",deviceName(app));
                Map<String,Object> enrollment=post(path,body);
                preferences(app).edit().putString("code",Json.string(enrollment,"code")).putString("ticket",Json.string(enrollment,"ticket")).putBoolean("linked",false).commit();
                enroll(app,callback);
            } catch(SimpleHttp.HttpException e){callback.failed(message(e,"That did not work. Try again."));}
            catch(Exception e){callback.failed("Could not reach Backstage. Check your internet connection and try again.");}
        },"backstage-verify").start();
    }
    private static String message(SimpleHttp.HttpException e,String fallback) {
        try{String m=Json.string(Json.object(e.body),"message");if(m!=null&&!m.isBlank())return m;}catch(RuntimeException ignored){}
        return fallback;
    }
    static void start(Activity activity, boolean create, Callback callback) {
        new Thread(() -> {
            try {
                String installation=installation(activity);
                Map<String,Object> start=post("/options", Json.mapOf("mode",create?"register":"authenticate","installation",installation));
                String ticket=Json.string(start,"ticket"), options=Json.stringify(start.get("options"));
                activity.runOnUiThread(() -> {
                    if(activity.isDestroyed()){callback.failed("Sign-in stopped. Try again.");return;}
                    CredentialManager manager=CredentialManager.create(activity);
                    if(create)manager.createCredentialAsync(activity,new CreatePublicKeyCredentialRequest(options),new CancellationSignal(),activity.getMainExecutor(),
                        new CredentialManagerCallback<CreateCredentialResponse,CreateCredentialException>() {
                            public void onResult(CreateCredentialResponse r) {
                                if(r instanceof CreatePublicKeyCredentialResponse)finish(activity,ticket,((CreatePublicKeyCredentialResponse)r).getRegistrationResponseJson(),callback);
                                else callback.failed("No passkey was saved. Try again.");
                            }
                            public void onError(CreateCredentialException e) {callback.failed(e instanceof CreateCredentialCancellationException?"Sign-in cancelled.":"Could not save a passkey. Check that your phone has a screen lock and a password manager enabled, then try again.");}
                        });
                    else manager.getCredentialAsync(activity,new GetCredentialRequest.Builder().addCredentialOption(new GetPublicKeyCredentialOption(options)).build(),new CancellationSignal(),activity.getMainExecutor(),
                        new CredentialManagerCallback<GetCredentialResponse,GetCredentialException>() {
                            public void onResult(GetCredentialResponse r) {
                                if(r.getCredential() instanceof PublicKeyCredential)finish(activity,ticket,((PublicKeyCredential)r.getCredential()).getAuthenticationResponseJson(),callback);
                                else callback.failed("Choose your Backstage passkey and try again.");
                            }
                            public void onError(GetCredentialException e) {callback.failed(e instanceof GetCredentialCancellationException?"Sign-in cancelled.":"No passkey found. Use a connection code from another phone, or create an account.");}
                        });
                });
            }catch(Exception e){callback.failed("Could not reach Backstage. Check your internet connection and try again.");}
        },"backstage-sign-in").start();
    }
    private static void finish(Context context,String ticket,String response,Callback callback) {
        Context app=context.getApplicationContext();
        new Thread(() -> {
            try {
                Map<String,Object> enrollment=post("/verify",Json.mapOf("ticket",ticket,"response",Json.object(response),"deviceName",deviceName(app)));
                preferences(app).edit().putString("code",Json.string(enrollment,"code")).putString("ticket",Json.string(enrollment,"ticket")).putBoolean("linked",false).commit();
                enroll(app,callback);
            } catch(Exception e){callback.failed("Could not finish sign-in. Tap Sign in and use your saved passkey to try again.");}
        },"backstage-verify").start();
    }
    private static void enroll(Context app,Callback callback) {
        new Thread(() -> {
            try {
                String code=preferences(app).getString("code",""),enrollmentTicket=preferences(app).getString("ticket","");
                AgentConfig current=AgentConfig.load(app);
                CompletableFuture<RelayLinker.Result> linked=CompletableFuture.supplyAsync(() -> {
                    try {
                        if(preferences(app).getBoolean("linked",false)&&current.hasAssistant()){
                            com.deviceagent.daemon.DaemonConfig.Link link=current.links.get(current.links.size()-1);
                            return new RelayLinker.Result(current.relayUrl,current.deviceToken,current.deviceName,current.deviceId,link.agentId,link.linkKey);
                        }
                        RelayLinker.Result result=RelayLinker.connect(SetupRules.DEFAULT_RELAY,code,current.relayUrl,current.deviceToken,android.os.Build.MODEL,android.os.Build.VERSION.SDK_INT,RelayLinker.HTTP);
                        current.relayUrl=result.relay;current.deviceToken=result.token;current.deviceName=result.name==null?"":result.name;current.deviceId=result.deviceId==null?"":result.deviceId;
                        current.upsertLink(result.agentId,result.linkKey);current.save(app);preferences(app).edit().putBoolean("linked",true).commit();return result;
                    }
                    catch(Exception e){throw new java.util.concurrent.CompletionException(e);}
                });
                long until=System.currentTimeMillis()+90000;boolean registered=false;
                while(System.currentTimeMillis()<until) {
                    if(linked.isCompletedExceptionally())linked.get();
                    Map<String,Object> result=post("/enroll",Json.mapOf("ticket",enrollmentTicket));
                    if(result.get("deviceId") instanceof String){registered=true;break;}
                    Thread.sleep(500);
                }
                if(!registered)throw new IllegalStateException("Enrollment timed out");
                RelayLinker.Result result=linked.get(30,TimeUnit.SECONDS);
                preferences(app).edit().remove("ticket").remove("code").remove("linked").commit();callback.ready(result);
            } catch(Exception e){
                if(e instanceof SimpleHttp.HttpException&&((SimpleHttp.HttpException)e).status==410)preferences(app).edit().remove("ticket").remove("code").remove("linked").commit();
                callback.failed("Setup did not finish. Reopen Backstage to resume, or sign in again.");
            }
        },"backstage-enroll").start();
    }
}
