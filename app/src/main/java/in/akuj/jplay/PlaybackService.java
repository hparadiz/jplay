package in.akuj.jplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceView;
import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.interfaces.IVLCVout;

/** One service-owned player. All public methods and native controls run on main. */
public final class PlaybackService extends Service {
    static final String OPEN="in.akuj.jplay.OPEN", PLAY="in.akuj.jplay.PLAY", PAUSE="in.akuj.jplay.PAUSE",
        TOGGLE="in.akuj.jplay.TOGGLE", STOP="in.akuj.jplay.STOP", BACK="in.akuj.jplay.BACK", NEXT="in.akuj.jplay.NEXT";
    private static final int NOTIFICATION=73001;
    private static final String CHANNEL="playback";
    private static PlaybackService instance;private static int pendingNativeReleases;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService disk=Executors.newSingleThreadExecutor(), nativeIo=Executors.newSingleThreadExecutor();
    private final ArrayList<Runnable> listeners=new ArrayList<>();
    private final Binder binder=new LocalBinder();
    private JPlay app;
    private Profile profile;
    private SharedPreferences preferences;
    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;
    private Uri uri;
    private String title="Just Play", status="Ready", key="";
    private long position,duration,lastSave,lastSession,sleepDeadline;
    private boolean desiredPlay,started,recorded,failed,ended,disposing,pendingOpen,destroyed,foreground,focusResume;
    private int generation,videoTrack=Integer.MIN_VALUE;
    private Object surfaceOwner;
    private SurfaceView video,subtitles;
    private IVLCVout.OnNewVideoLayoutListener layoutListener;

    public final class LocalBinder extends Binder { PlaybackService service(){return PlaybackService.this;} }
    public static PlaybackService peek(){return instance;}
    public boolean hasMedia(){return uri!=null&&!destroyed;}
    public boolean isPlaying(){return player!=null&&player.isPlaying()&&!failed&&!ended;}
    public Uri currentUri(){return uri;}
    public String mediaTitle(){return title;}
    public long positionMs(){if(player!=null&&started&&!failed&&!ended&&player.getTime()>=0)position=player.getTime();return Math.max(0,position);}
    public void addListener(Runnable listener){if(!listeners.contains(listener))listeners.add(listener);}
    public void removeListener(Runnable listener){listeners.remove(listener);}
    public static Intent resumeIntent(Context context){
        Intent intent=new Intent(context,PlayerActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("resumeOnly",true);
        if(instance!=null&&instance.uri!=null)intent.putExtra("uri",instance.uri.toString());
        return intent;
    }
    public static void toggle(Context context){if(instance!=null)instance.togglePlayback();}
    public static void stop(Context context){if(instance!=null)instance.stopPlayback();}
    static void open(Context context,Uri uri,long startMs){context.startForegroundService(new Intent(context,PlaybackService.class).setAction(OPEN).putExtra("uri",uri.toString()).putExtra("startMs",startMs));}
    static PendingIntent action(Context context,String action){return PendingIntent.getService(context,action.hashCode(),new Intent(context,PlaybackService.class).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    MediaPlayer player(){return player;}
    boolean started(){return started;}
    boolean failed(){return failed;}
    boolean ended(){return ended;}
    boolean wantsPlay(){return desiredPlay;}
    long durationMs(){return duration;}
    long sleepDeadline(){return sleepDeadline;}
    String status(){return status;}
    String title(){return title;}

    private final BroadcastReceiver noisy=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){Log.i("JPlay","audio route became noisy; pausing");pause();}};
    private final Runnable network=()->{
        if(hasMedia()&&!app.bindLocalNetwork()){
            saveProgress();failed=true;desiredPlay=false;pendingOpen=false;focusResume=false;
            status="Network disconnected. Reconnect, then tap Retry.";releasePlayer();audio.abandonAudioFocusRequest(focus);changed();
        }
    };
    private final Runnable sleep=()->{sleepDeadline=0;pause();status="Sleep timer finished";changed();};
    private final Runnable tick=new Runnable(){@Override public void run(){
        if(destroyed)return;
        if(hasMedia()){
            positionMs();if(player!=null&&player.getLength()>0)duration=player.getLength();
            long now=SystemClock.elapsedRealtime();
            if(sleepDeadline>0&&now>=sleepDeadline){main.removeCallbacks(sleep);sleep.run();}
            if(now-lastSave>=10000){saveProgress();lastSave=now;String stats=diagnostics();Log.i("JPlay",stats);CrashReports.get(PlaybackService.this).playbackState(stats);}
            if(now-lastSession>=1000){updateSession();lastSession=now;}
            notifyListeners();
        }
        main.postDelayed(this,500);
    }};

    @Override public void onCreate(){
        super.onCreate();instance=this;app=(JPlay)getApplication();preferences=getSharedPreferences("player-options",MODE_PRIVATE);
        audio=getSystemService(AudioManager.class);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setOnAudioFocusChangeListener(change->{
                Log.i("JPlay","audio focus change="+change);
                if(change==AudioManager.AUDIOFOCUS_GAIN){boolean resume=focusResume;focusResume=false;if(resume&&desiredPlay&&!failed)play();}
                else {
                    focusResume=change!=AudioManager.AUDIOFOCUS_LOSS&&desiredPlay;
                    if(!focusResume)desiredPlay=false;
                    if(player!=null)player.pause();saveProgress();status="Paused for another audio app";changed();
                }
            },main).build();
        cpu=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"JustPlay:playback");cpu.setReferenceCounted(false);
        wifi=((WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"JustPlay:stream");wifi.setReferenceCounted(false);
        session=new MediaSession(this,"Just Play");
        session.setCallback(new MediaSession.Callback(){
            @Override public void onPlay(){play();}
            @Override public void onPause(){pause();}
            @Override public void onStop(){stopPlayback();}
            @Override public void onSeekTo(long p){seek(p);}
            @Override public void onFastForward(){jump(10000);}
            @Override public void onRewind(){jump(-10000);}
            @Override public void onSkipToNext(){jump(10000);}
            @Override public void onSkipToPrevious(){jump(-10000);}
            @Override public void onCustomAction(String action,android.os.Bundle extras){if(STOP.equals(action))stopPlayback();}
        },main);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"Playback",NotificationManager.IMPORTANCE_LOW));
        if(Build.VERSION.SDK_INT>=33)registerReceiver(noisy,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        app.listen(network);main.post(tick);
    }
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){if(!hasMedia())stopSelf();return START_NOT_STICKY;}
        String command=intent.getAction();
        if(OPEN.equals(command)||PLAY.equals(command)||PAUSE.equals(command)||TOGGLE.equals(command)||STOP.equals(command)||BACK.equals(command)||NEXT.equals(command))Log.i("JPlay","service command="+command);
        if(OPEN.equals(command)){
            // Meet the foreground-service deadline before authentication/metadata work.
            ensureForeground();
            try{openMedia(Uri.parse(intent.getStringExtra("uri")),intent.getLongExtra("startMs",-1));}
            catch(Exception e){status="Could not open the NAS video";if(!hasMedia()){stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;stopSelf();}else changed();}
        } else if(hasMedia()){
            if(PLAY.equals(command))play();else if(PAUSE.equals(command))pause();else if(TOGGLE.equals(command))togglePlayback();
            else if(STOP.equals(command))stopPlayback();else if(BACK.equals(command))jump(-10000);else if(NEXT.equals(command))jump(10000);
        }else stopSelf();
        return START_NOT_STICKY;
    }
    /** The file after this one in the same folder: a finished episode rolls into the next. */
    private Uri following(){
        if(uri==null)return null;
        String address=uri.toString();int cut=address.lastIndexOf('/');if(cut<=0)return null;
        java.util.ArrayList<LibraryStore.Item> siblings=LibraryStore.get(this).folder(Uri.parse(address.substring(0,cut)));
        for(int n=0;n+1<siblings.size();n++)if(siblings.get(n).uri.equals(uri))return siblings.get(n+1).uri;
        return null;
    }
    private void openMedia(Uri next,long startMs) throws Exception {
        Profile nextProfile=Profile.load(this);if(!nextProfile.contains(next)&&!Streams.remote(next))throw new IllegalArgumentException();
        if(next.equals(uri)&&startMs<0){changed();return;}
        saveProgress();releasePlayer();
        boolean different=!next.equals(uri);uri=next;profile=nextProfile;key=LibraryStore.key(uri)+".";
        if(different){recorded=false;duration=0;setSleepMinutes(0);}
        title=Streams.title(uri,LibraryStore.get(this).item(uri).title());position=startMs>=0?startMs:LibraryStore.get(this).position(uri);
        videoTrack=preferences.getInt(key+"video",Integer.MIN_VALUE);
        started=false;failed=false;ended=false;desiredPlay=true;focusResume=false;pendingOpen=true;status="Connecting to NAS…";
        session.setActive(true);session.setSessionActivity(PendingIntent.getActivity(this,1,resumeIntent(this),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        updateMetadata();changed();createPending();
    }
    void restart(long startMs){
        if(!hasMedia())return;saveProgress();releasePlayer();position=Math.max(0,startMs);started=false;failed=false;ended=false;
        pendingOpen=true;status="Connecting to NAS…";changed();createPending();
    }
    void restartPlaying(long startMs){desiredPlay=true;focusResume=false;restart(startMs);}
    /**
     * A stream that opens but never reads a byte looks exactly like buffering forever, which
     * is what an automatic roll into the next episode can leave behind when the NAS has not
     * finished letting go of the last one. Give it one restart before saying so out loud.
     */
    private void createPending(){
        if(!pendingOpen||disposing||destroyed||!hasMedia())return;
        if(pendingNativeReleases>0){main.postDelayed(this::createPending,200);return;}
        pendingOpen=false;
        if(!app.bindLocalNetwork()){failed=true;desiredPlay=false;status="Connect to your network, then tap Retry.";changed();return;}
        ensureForeground();
        if(desiredPlay&&audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){failed=true;desiredPlay=false;status="Audio is busy. Tap Retry when ready.";changed();return;}
        final int ticket=++generation;
        player=new MediaPlayer(app.vlc());
        final MediaPlayer created=player;
        player.getVLCVout().addCallback(new IVLCVout.Callback(){
            @Override public void onSurfacesCreated(IVLCVout out){main.post(()->{
                if(created==player&&ticket==generation&&surfaceOwner!=null&&videoTrack!=Integer.MIN_VALUE)player.setVideoTrack(videoTrack);
            });}
            @Override public void onSurfacesDestroyed(IVLCVout out){
                if(created==player&&started&&player.getVideoTrack()>=0)videoTrack=player.getVideoTrack();
            }
        });
        player.setEventListener(event->{if(ticket!=generation||player==null||destroyed)return;
            switch(event.type){
                case MediaPlayer.Event.Opening:status="Opening from NAS…";break;
                case MediaPlayer.Event.Buffering:if(!failed)status=event.getBuffering()<100?"Buffering "+(int)event.getBuffering()+"%":"Direct from NAS";break;
                case MediaPlayer.Event.Playing:
                    if(!started){started=true;if(!recorded){recorded=true;Uri target=uri;if(!Streams.remote(target))disk.execute(()->LibraryStore.get(this).begin(target));}applyPreferences();indexMedia();}
                    if(!desiredPlay||focusResume)player.pause();
                    if(surfaceOwner==null)player.setVideoTrackEnabled(false);
                    status=desiredPlay&&!focusResume?"Direct from NAS":"Paused";break;
                case MediaPlayer.Event.Paused:saveProgress();status=focusResume?"Paused for another audio app":"Paused";break;
                case MediaPlayer.Event.ESAdded:if(started)indexMedia();break;
                case MediaPlayer.Event.LengthChanged:if(player.getLength()>0){duration=player.getLength();updateMetadata();}break;
                case MediaPlayer.Event.EndReached:
                    ended=true;position=0;saveProgress();status="Finished";
                    if(preferences.getBoolean("repeat",false)){desiredPlay=true;main.post(()->{if(ticket==generation&&hasMedia()&&ended)restart(0);});break;}
                    Uri following=following();
                    if(following!=null){
                        desiredPlay=true;status="Next episode…";
                        main.post(()->{
                            if(ticket!=generation||!hasMedia()||!ended)return;
                            try{openMedia(following,-1);}catch(Exception e){Log.w("JPlay","could not roll into the next episode",e);}
                        });
                        break;
                    }
                    desiredPlay=false;audio.abandonAudioFocusRequest(focus);break;
                case MediaPlayer.Event.EncounteredError:
                    saveProgress();failed=true;desiredPlay=false;status="Playback failed. Check the network / NAS login, or choose Software decoder.";audio.abandonAudioFocusRequest(focus);break;
                default:return;
            }
            changed();
        });
        attachCurrent();
        Media media=app.media(profile,uri);app.configureDecoder(media,uri);
        Streams.Stream stream=Streams.get(uri);if(stream!=null&&stream.audio!=null)media.addSlave(new IMedia.Slave(IMedia.Slave.Type.Audio,4,stream.audio));
        Log.i("JPlay","create player generation="+ticket+" decoderPreference="+(app.softwareDecoder(uri)?"software":"automatic")+" position="+position+"ms surface="+(surfaceOwner!=null));
        if(position>0)media.addOption(":start-time="+(position/1000.0));
        player.setMedia(media);media.release();player.play();changed();
    }
    void play(){
        if(!hasMedia())return;desiredPlay=true;focusResume=false;ensureForeground();
        if(failed||ended||player==null){restart(ended?0:position);return;}
        if(!app.bindLocalNetwork()){network.run();return;}
        if(audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){player.play();status="Direct from NAS";}
        else{desiredPlay=false;status="Audio is busy";}changed();
    }
    void pause(){desiredPlay=false;focusResume=false;if(player!=null)player.pause();saveProgress();audio.abandonAudioFocusRequest(focus);status="Paused";changed();}
    void togglePlayback(){if(desiredPlay&&!failed&&!ended)pause();else play();}
    void seek(long p){if(player!=null&&player.isSeekable()){player.setTime(Math.max(0,Math.min(durationMs()>0?durationMs():Long.MAX_VALUE,p)));position=Math.max(0,p);saveProgress();changed();}}
    void jump(long delta){seek(positionMs()+delta);}
    void setSleepMinutes(int minutes){main.removeCallbacks(sleep);sleepDeadline=minutes<=0?0:SystemClock.elapsedRealtime()+minutes*60000L;if(minutes>0)main.postDelayed(sleep,minutes*60000L);}
    void optionsChanged(){updateSession();updateNotification();notifyListeners();}
    boolean selectVideoTrack(int track){
        if(player==null||!player.setVideoTrack(track))return false;
        videoTrack=track;preferences.edit().putInt(key+"video",track).apply();return true;
    }
    void stopPlayback(){
        Log.i("JPlay","explicit playback stop");
        saveProgress();desiredPlay=false;focusResume=false;pendingOpen=false;ended=false;failed=false;uri=null;setSleepMinutes(0);
        status="Stopped";session.setActive(false);audio.abandonAudioFocusRequest(focus);releasePlayer();changed();
        stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;
        if(!disposing)stopSelf();
    }
    private void releasePlayer(){
        if(player==null)return;
        MediaPlayer old=player;player=null;generation++;disposing=true;pendingNativeReleases++;old.setEventListener(null);
        // Detach views on main; stop/release ownership is exclusive after this point.
        old.getVLCVout().detachViews();old.setVolume(0);notifyListeners();
        nativeIo.execute(()->{
            try{old.stop();}finally{new Handler(Looper.getMainLooper()).post(()->{
                try{old.release();}finally{disposing=false;pendingNativeReleases--;if(!destroyed){if(pendingOpen)createPending();else if(!hasMedia())stopSelf();}}
            });}
        });
    }
    void attach(Object owner,SurfaceView video,SurfaceView subtitles,IVLCVout.OnNewVideoLayoutListener listener){
        if(surfaceOwner==owner&&this.video==video&&this.subtitles==subtitles){if(player!=null&&!player.getVLCVout().areViewsAttached())attachCurrent();return;}
        Log.i("JPlay","attach video surface generation="+generation);
        if(player!=null)player.getVLCVout().detachViews();
        surfaceOwner=owner;this.video=video;this.subtitles=subtitles;layoutListener=listener;attachCurrent();
    }
    void detach(Object owner){
        if(surfaceOwner!=owner)return;
        Log.i("JPlay","detach video surface; background audio retained generation="+generation);
        surfaceOwner=null;video=null;subtitles=null;layoutListener=null;
        if(player!=null)player.getVLCVout().detachViews();saveProgress();
    }
    private void attachCurrent(){
        if(player==null||surfaceOwner==null||video==null)return;
        final MediaPlayer attached=player;final Object owner=surfaceOwner;
        IVLCVout out=player.getVLCVout();out.setVideoView(video);out.setSubtitlesView(subtitles);
        out.attachViews((v,w,h,vw,vh,sn,sd)->{if(attached==player&&owner==surfaceOwner&&layoutListener!=null)layoutListener.onNewVideoLayout(v,w,h,vw,vh,sn,sd);});
    }
    private void applyPreferences(){
        player.setRate(Math.max(.25f,Math.min(3f,preferences.getFloat("rate",1f))));
        player.setAudioDelay(preferences.getLong(key+"audioDelay",0));player.setSpuDelay(preferences.getLong(key+"subtitleDelay",0));
        int preset=preferences.getInt("equalizer",-1);if(preset>=0&&preset<MediaPlayer.Equalizer.getPresetCount())player.setEqualizer(MediaPlayer.Equalizer.createFromPreset(preset));
        String external=preferences.getString(key+"external","");if(!external.isEmpty()&&new File(external).isFile())player.addSlave(IMedia.Slave.Type.Subtitle,external,true);
        int audioTrack=preferences.getInt(key+"audio",Integer.MIN_VALUE),sub=preferences.getInt(key+"subtitle",Integer.MIN_VALUE);
        MediaPlayer.TrackDescription[] tracks=player.getAudioTracks();if(tracks!=null)for(MediaPlayer.TrackDescription t:tracks)if(t.id==audioTrack)player.setAudioTrack(audioTrack);
        tracks=player.getSpuTracks();if(tracks!=null)for(MediaPlayer.TrackDescription t:tracks)if(t.id==sub)player.setSpuTrack(sub);
        if(surfaceOwner!=null&&videoTrack!=Integer.MIN_VALUE)player.setVideoTrack(videoTrack);
    }
    private void saveProgress(){
        if(!hasMedia()||!started||!recorded||Streams.remote(uri))return;
        long p=ended?0:positionMs();boolean complete=ended||(duration>0&&duration-p<10000);if(complete)p=0;
        getSharedPreferences("positions",MODE_PRIVATE).edit().putLong(uri.toString(),p).apply();
        final long saved=p,length=duration;final Uri target=uri;
        disk.execute(()->LibraryStore.get(this).record(target,saved,length,complete));
    }
    private void indexMedia(){
        if(player==null||Streams.remote(uri))return;IMedia media=player.getMedia();if(media==null)return;final Uri target=uri;
        disk.execute(()->{try{LibraryStore.get(this).updateMedia(target,media);}finally{media.release();}});
    }
    String diagnostics(){
        if(player==null)return "position="+position+"ms duration="+duration+"ms playing=false";
        IMedia media=player.getMedia();
        try{IMedia.Stats s=media==null?null:media.getStats();return String.format(Locale.ROOT,"position=%dms duration=%dms playing=%s seekable=%s decoderPreference=%s surface=%s videoTrack=%d generation=%d%s",positionMs(),player.getLength(),isPlaying(),player.isSeekable(),app.softwareDecoder(uri)?"software":"automatic",player.getVLCVout().areViewsAttached(),player.getVideoTrack(),generation,s==null?"":String.format(Locale.ROOT," decoded=%d displayed=%d lost=%d audioDecoded=%d readBytes=%d",s.decodedVideo,s.displayedPictures,s.lostPictures,s.decodedAudio,s.readBytes));}
        finally{if(media!=null)media.release();}
    }
    private void changed(){updateLocks();updateSession();updateNotification();notifyListeners();}
    private void notifyListeners(){for(Runnable listener:new ArrayList<>(listeners))listener.run();}
    private void updateLocks(){
        boolean active=hasMedia()&&desiredPlay&&!focusResume&&!failed&&!ended;
        if(active){if(!cpu.isHeld())cpu.acquire();if(!wifi.isHeld())wifi.acquire();}
        else{if(cpu.isHeld())cpu.release();if(wifi.isHeld())wifi.release();}
    }
    private void updateMetadata(){session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,title).putLong(MediaMetadata.METADATA_KEY_DURATION,duration).build());}
    private void updateSession(){
        int state=!hasMedia()?PlaybackState.STATE_STOPPED:failed?PlaybackState.STATE_ERROR:ended?PlaybackState.STATE_STOPPED:isPlaying()?PlaybackState.STATE_PLAYING:desiredPlay&&!focusResume?PlaybackState.STATE_BUFFERING:PlaybackState.STATE_PAUSED;
        long actions=PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE|PlaybackState.ACTION_STOP|PlaybackState.ACTION_SEEK_TO|PlaybackState.ACTION_FAST_FORWARD|PlaybackState.ACTION_REWIND|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions).setState(state,positionMs(),isPlaying()?player.getRate():0)
            .addCustomAction(new PlaybackState.CustomAction.Builder(STOP,"Stop",android.R.drawable.ic_menu_close_clear_cancel).build()).build());
    }
    private Notification notification(){
        boolean pause=desiredPlay&&!failed&&!ended;
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(status)
            .setContentIntent(PendingIntent.getActivity(this,1,resumeIntent(this),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE))
            .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_TRANSPORT).setOnlyAlertOnce(true).setShowWhen(false)
            .setOngoing(pause).setDeleteIntent(action(this,STOP))
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_rew,"Back 10 seconds",action(this,BACK)).build())
            .addAction(new Notification.Action.Builder(pause?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play,pause?"Pause":"Play",action(this,pause?PAUSE:PLAY)).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_ff,"Forward 10 seconds",action(this,NEXT)).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"Stop",action(this,STOP)).build())
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0,1,2)).build();
    }
    private void ensureForeground(){
        if(foreground)return;
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIFICATION,notification());foreground=true;
    }
    private void updateNotification(){if(foreground&&hasMedia())getSystemService(NotificationManager.class).notify(NOTIFICATION,notification());}
    @Override public void onTaskRemoved(Intent rootIntent){saveProgress();/* Keep explicit background playback until Stop. */}
    @Override public void onDestroy(){
        Log.i("JPlay","playback service destroyed");
        saveProgress();destroyed=true;desiredPlay=false;pendingOpen=false;uri=null;main.removeCallbacksAndMessages(null);
        releasePlayer();surfaceOwner=null;video=null;subtitles=null;layoutListener=null;updateLocks();
        app.unlisten(network);unregisterReceiver(noisy);audio.abandonAudioFocusRequest(focus);session.setActive(false);session.release();
        stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;if(instance==this)instance=null;notifyListeners();listeners.clear();disk.shutdown();nativeIo.shutdown();super.onDestroy();
    }
}
