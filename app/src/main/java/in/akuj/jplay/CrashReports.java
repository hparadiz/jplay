package in.akuj.jplay;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ApplicationExitInfo;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.hierynomus.mssmb2.SMBApiException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local-first, private crash collection. Crashing threads never touch the network. */
final class CrashReports {
    private static final int JOB_ID=73102, MAX_REPORTS=64;
    private static CrashReports instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final File directory;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean uploading=new AtomicBoolean(), crashing=new AtomicBoolean();
    private volatile boolean enabled;
    private volatile String lastResult, playback="";
    private volatile String[] secrets=new String[0];
    private boolean initialized;

    static synchronized CrashReports get(Context context) {
        if(instance==null)instance=new CrashReports(context.getApplicationContext());
        return instance;
    }
    private CrashReports(Context context) {
        this.context=context; prefs=context.getSharedPreferences("crash-reports",Context.MODE_PRIVATE);
        directory=new File(context.getFilesDir(),"crash-queue"); directory.mkdirs();
        enabled=prefs.getBoolean("enabled",true);
        lastResult=prefs.getString("result","No reports sent yet.");
    }
    synchronized void start() {
        if(initialized)return; initialized=true;
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error) -> {
            if(enabled&&crashing.compareAndSet(false,true)) {
                try {
                    JSONObject report=base("java_exception",false);
                    JSONArray causes=new JSONArray(); IdentityHashMap<Throwable,Boolean> seen=new IdentityHashMap<>();
                    for(Throwable t=error;t!=null&&causes.length()<8&&!seen.containsKey(t);t=t.getCause()) {
                        seen.put(t,true); JSONObject cause=new JSONObject().put("class",t.getClass().getName());
                        JSONArray frames=new JSONArray();
                        for(StackTraceElement f:t.getStackTrace()) {
                            if(frames.length()>=96)break;
                            frames.put(f.getClassName()+"."+f.getMethodName()+":"+f.getLineNumber());
                        }
                        causes.put(cause.put("frames",frames));
                    }
                    report.put("causes",causes).put("messagePolicy","Exception messages and thread names omitted to protect credentials.");
                    write(report);
                } catch(Throwable ignored) { /* Keep Android's original crash handling even under OOM. */ }
            }
            if(previous!=null)previous.uncaughtException(thread,error);
            else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
        });
        schedule();
        worker.execute(() -> { loadSecrets(); collectExits(); uploadNow(); });
    }
    boolean enabled(){return enabled;}
    void setEnabled(boolean value) {
        enabled=value; prefs.edit().putBoolean("enabled",value).apply();
        schedule(); if(value)uploadNow();
    }
    String status(){return files().length+" pending · "+lastResult;}
    void playbackState(String state) { playback=state.substring(0,Math.min(2000,state.length())); }
    void enqueueSample() {
        worker.execute(() -> {
            try {
                write(base("sample_report",true).put("note","SAMPLE ONLY — upload validation; no crash occurred."));
                result("Sample report queued."); uploadNow();
            } catch(Exception e){result("Could not save the sample report locally.");}
        });
    }
    void uploadNow(){uploadNow(null);}
    void uploadNow(Runnable finished) {
        if(!enabled||!uploading.compareAndSet(false,true)){if(finished!=null)main.post(finished);return;}
        worker.execute(() -> {
            try { upload(); }
            finally { uploading.set(false); if(finished!=null)main.post(finished); }
        });
    }
    private void upload() {
        if(!enabled)return;
        File[] queue=files(); if(queue.length==0)return;
        if(!((JPlay)context).bindLocalNetwork()){result("Waiting for the network.");return;}
        Profile profile;
        try { profile=Profile.load(context); rememberSecrets(profile); }
        catch(Exception e){result("NAS login is unavailable. Open NAS settings.");return;}
        if(profile.host.isEmpty()||profile.share.isEmpty()){result("Add a NAS connection to upload reports.");return;}
        int sent=0;
        for(File file:queue) {
            if(!enabled||!((JPlay)context).bindLocalNetwork())break;
            try {
                byte[] bytes=Files.readAllBytes(file.toPath());
                // Re-redact with the current credentials before anything leaves the phone.
                JSONObject report=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
                scrub(report); bytes=report.toString(2).getBytes(StandardCharsets.UTF_8);
                NasUploader.upload(profile,file.getName(),bytes);
                Files.delete(file.toPath()); sent++;
                result("Uploaded "+file.getName()+" to JPlay/Crashes.");
                prefs.edit().putLong("lastSuccess",System.currentTimeMillis()).apply();
                if(sent>=8)break; // Bound each background job's work.
            } catch(Exception e) {
                if(e instanceof SMBApiException) {
                    String code=((SMBApiException)e).getStatus().name();
                    result(code.contains("ACCESS_DENIED")?"NAS denied write access to JPlay/Crashes. Report kept locally."
                        :code.contains("LOGON")?"NAS login failed. Report kept locally.":"NAS upload failed ("+code+"). Report kept locally.");
                } else result("NAS unreachable or upload failed. Report kept locally; will retry.");
                // One attempt per report per pass; later network/start/JobScheduler events retry.
                break;
            }
        }
    }
    private void schedule() {
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        if(!enabled){scheduler.cancel(JOB_ID);return;}
        if(scheduler.getPendingJob(JOB_ID)==null) scheduler.schedule(new JobInfo.Builder(JOB_ID,new ComponentName(context,CrashUploadJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setRequiresStorageNotLow(true)
            .setPeriodic(15*60*1000L).setPersisted(true).build());
    }
    private void loadSecrets(){try {rememberSecrets(Profile.load(context));}catch(Exception ignored){}}
    void rememberSecrets(Profile p) { secrets=new String[]{p.password,p.user,p.domain,p.host,p.share}; }
    private String redact(String text) {
        for(String secret:secrets)if(secret!=null&&!secret.isEmpty()) {
            text=text.replace(secret,"[redacted]").replace(android.net.Uri.encode(secret),"[redacted]");
        }
        return text;
    }
    private void scrub(Object value) throws Exception {
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value;
            java.util.Iterator<String> keys=object.keys();
            while(keys.hasNext()){String key=keys.next();Object child=object.get(key);if(child instanceof String)object.put(key,redact((String)child));else scrub(child);}
        } else if(value instanceof JSONArray) {
            JSONArray array=(JSONArray)value;
            for(int i=0;i<array.length();i++){Object child=array.get(i);if(child instanceof String)array.put(i,redact((String)child));else scrub(child);}
        }
    }
    private JSONObject base(String kind,boolean sample) throws Exception {
        return new JSONObject().put("schema",1).put("id",UUID.randomUUID().toString()).put("timestampMs",System.currentTimeMillis())
            .put("kind",kind).put("sample",sample).put("appVersion",context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName)
            .put("androidApi",Build.VERSION.SDK_INT).put("abi",Build.SUPPORTED_ABIS[0])
            .put("playback",playback).put("privacy","No credentials, media names, network addresses, log buffers or memory contents.");
    }
    private void write(JSONObject report) throws Exception {
        // Redact values before JSON encoding so unusual passwords cannot corrupt JSON.
        String reportId=report.getString("id");
        scrub(report); byte[] bytes=report.toString(2).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>128*1024)throw new IllegalArgumentException("Report too large");
        File[] queue=files();
        if(queue.length>=MAX_REPORTS)Files.deleteIfExists(queue[0].toPath());
        String prefix=report.optBoolean("sample")?"sample-":"crash-";
        AtomicFile file=new AtomicFile(new File(directory,prefix+reportId+".json"));
        FileOutputStream out=null;
        try { out=file.startWrite();out.write(bytes);file.finishWrite(out); }
        catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
    }
    private File[] files() {
        File[] list=directory.listFiles((dir,name)->name.endsWith(".json"));
        if(list==null)return new File[0];
        Arrays.sort(list,Comparator.comparingLong(File::lastModified));return list;
    }
    private void result(String text){lastResult=text;prefs.edit().putString("result",text).apply();}
    private void collectExits() {
        if(!enabled||Build.VERSION.SDK_INT<30)return;
        long since=prefs.getLong("lastExit",0),latest=since;
        try {
            for(ApplicationExitInfo exit:context.getSystemService(ActivityManager.class).getHistoricalProcessExitReasons(context.getPackageName(),0,24)) {
                if(exit.getTimestamp()<=since)continue;
                int reason=exit.getReason();
                if(reason!=ApplicationExitInfo.REASON_CRASH&&reason!=ApplicationExitInfo.REASON_CRASH_NATIVE&&reason!=ApplicationExitInfo.REASON_ANR) {
                    latest=Math.max(latest,exit.getTimestamp());continue;
                }
                JSONObject report=base(reason==ApplicationExitInfo.REASON_CRASH_NATIVE?"native_exit":reason==ApplicationExitInfo.REASON_ANR?"anr_exit":"java_exit",false)
                    .put("exitTimestampMs",exit.getTimestamp()).put("reason",reason).put("status",exit.getStatus())
                    .put("pssKb",exit.getPss()).put("rssKb",exit.getRss());
                try(InputStream trace=exit.getTraceInputStream()) {
                    if(trace!=null) {
                        byte[] bytes=readBounded(trace,2*1024*1024);
                        if(reason==ApplicationExitInfo.REASON_CRASH_NATIVE&&Build.VERSION.SDK_INT>=31) report.put("native",NativeCrashSummary.read(bytes));
                        else {
                            JSONArray frames=new JSONArray();
                            for(String line:new String(bytes,StandardCharsets.UTF_8).split("\n")) {
                                // Only Java stack lines, not arbitrary log messages / thread names.
                                String s=line.trim();
                                if(s.matches("at [A-Za-z0-9_.$]+\\([A-Za-z0-9_.$ :?-]*\\)")&&frames.length()<160)frames.put(s);
                            }
                            report.put("javaFrames",frames);
                        }
                    }
                } catch(Exception e){report.put("traceStatus","Unavailable, oversized or unsupported; raw trace not stored.");}
                write(report);latest=Math.max(latest,exit.getTimestamp());
            }
            prefs.edit().putLong("lastExit",latest).apply();
        } catch(Exception e){result("Could not collect all Android exit records; will retry next launch.");}
    }
    static byte[] readBounded(InputStream in,int limit) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
        while((count=in.read(buffer))!=-1){if(out.size()+count>limit)throw new IllegalArgumentException("File too large");out.write(buffer,0,count);}
        return out.toByteArray();
    }
    void showSettings(Activity activity) {
        LinearLayout body=Ui.column(activity);Ui.pad(body,20);
        CheckBox toggle=new CheckBox(activity);toggle.setText("Send crash reports to my NAS");toggle.setTextColor(Ui.INK);toggle.setChecked(enabled);body.addView(toggle);
        body.addView(Ui.text(activity,"Destination: JPlay/Crashes in your configured share. Reports stay on this device until an upload is verified.",14,Ui.MUTED));
        TextView state=Ui.text(activity,status(),13,Ui.ACCENT);Ui.pad(state,12);body.addView(state);
        body.addView(Ui.text(activity,"Collects exception stacks and Android crash/ANR exit records. Native backtraces are included when Android provides them. Secrets and raw memory are excluded. Up to 64 reports are retained locally.",12,Ui.MUTED));
        android.widget.ScrollView scroll=new android.widget.ScrollView(activity);scroll.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Crash reports").setView(scroll).setPositiveButton("Done",null).create();
        toggle.setOnCheckedChangeListener((b,value)->setEnabled(value));
        body.addView(Ui.button(activity,"Upload pending reports",this::uploadNow));
        body.addView(Ui.button(activity,"Send labelled sample report",() -> {
            if(enabled)enqueueSample();else state.setText("Enable reports before sending a sample.");
        }));
        Runnable refresh=new Runnable(){public void run(){if(dialog.isShowing()){state.setText(status());main.postDelayed(this,1000);}}};
        dialog.setOnDismissListener(d->main.removeCallbacks(refresh));dialog.show();main.post(refresh);
    }
}
