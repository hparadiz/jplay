package in.akuj.jplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.util.Rational;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Toast;
import android.text.InputType;
import java.io.File;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.interfaces.IVLCVout;

public final class PlayerActivity extends Activity implements IVLCVout.OnNewVideoLayoutListener {
    private JPlay app;
    private Profile profile;
    private Uri uri;
    private MediaPlayer player;
    private FrameLayout root, videoFrame;
    private SurfaceView video, subtitles;
    private LinearLayout controls, top;
    private TextView message, time;
    private Button toggle;
    private Button unlock;
    private SeekBar seek;
    private AudioManager audio;
    private PlaybackService service;
    private boolean bound, foreground, firstOpen=true, hadMedia, refreshing, previouslyPlaying;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private SharedPreferences preferences;
    private String mediaKey, aspect="fit";
    private float rate=1f,zoom=1f,brightness=-1f;
    private long audioDelay,subtitleDelay,sleepDeadline,position,lastDuration,videoReadyAt,requestedStart=-1;
    private boolean locked,repeat,thumbnailSaved,thumbnailPending,choosingFile,wasPlayingBeforePicker;
    private boolean dragging,software,pausedByUser,ended,started,failed;
    private boolean tv,scrubbing;
    private android.widget.ImageButton menuButton;
    private LinearLayout episodes,episodeStrip;private android.widget.HorizontalScrollView episodeScroller;
    private final java.util.ArrayList<LibraryStore.Item> siblings=new java.util.ArrayList<>();
    private final java.util.ArrayList<View> episodeCards=new java.util.ArrayList<>();
    private int episodeIndex=-1,episodeChoice;private boolean episodesVisible;
    private final Runnable hideEpisodes=()->showEpisodes(false);
    private long scrubTarget,scrubOrigin;
    private int menuDepth,windowWidth,windowHeight,equalizerPreset=-1;
    private int videoWidth,videoHeight,visibleWidth,visibleHeight,sarNum=1,sarDen=1;
    private final Runnable hide=()->{if(player!=null&&player.isPlaying())showControls(false);};
    private final Runnable updateVideoLayout=this::layoutVideo;
    private final Runnable checkPipDismissal=()->{
        // Fullscreen expansion starts/resumes this Activity first. Screen-off and
        // keyguard transitions must retain the user's background playback.
        if(foreground||isInPictureInPictureMode()||isChangingConfigurations()||!hadMedia)return;
        if(!getSystemService(android.os.PowerManager.class).isInteractive()||getSystemService(android.app.KeyguardManager.class).isKeyguardLocked())return;
        PlaybackService running=PlaybackService.peek();
        if(running!=null&&uri!=null&&uri.equals(running.currentUri())){
            android.util.Log.i("JPlay","PiP dismissed while Activity stopped; stopping playback");
            PlaybackService.stop(this);finish();
        }
    };
    private final Runnable serviceChanged=this::refreshService;
    private final Runnable commitScrubSoon=this::commitScrub;
    private final android.content.ServiceConnection connection=new android.content.ServiceConnection(){
        @Override public void onServiceConnected(android.content.ComponentName name,android.os.IBinder binder){
            if(!foreground||!bound)return;
            service=((PlaybackService.LocalBinder)binder).service();service.addListener(serviceChanged);
            if(foreground){service.attach(PlayerActivity.this,video,subtitles,PlayerActivity.this);refreshService();}
        }
        @Override public void onServiceDisconnected(android.content.ComponentName name){service=null;player=null;message.setText("Playback stopped");updateResources();}
    };
    @Override protected void attachBaseContext(android.content.Context base){super.attachBaseContext(Ui.scaled(base));}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);app=(JPlay)getApplication();
        try{
            profile=Profile.load(this);
            String target=getIntent().getStringExtra("uri");
            if(target==null&&PlaybackService.peek()!=null&&PlaybackService.peek().currentUri()!=null)target=PlaybackService.peek().currentUri().toString();
            uri=Uri.parse(target);if(!profile.contains(uri)&&!Streams.remote(uri))throw new IllegalArgumentException();
        }catch(Exception e){uri=null;finish();return;}
        requestedStart=state==null&&!getIntent().getBooleanExtra("resumeOnly",false)?getIntent().getLongExtra("startMs",-1):-1;
        preferences=getSharedPreferences("player-options",MODE_PRIVATE);readOptions();
        locked=state!=null&&state.getBoolean("locked");
        audio=getSystemService(AudioManager.class);setVolumeControlStream(AudioManager.STREAM_MUSIC);
        tv=Ui.tv(this);
        buildUi();applyBrightness();
    }
    private void readOptions(){
        mediaKey=LibraryStore.key(uri)+".";software=app.softwareDecoder(uri);
        rate=preferences.getFloat("rate",1f);aspect=preferences.getString("aspect","fit");zoom=preferences.getFloat("zoom",1f);
        brightness=preferences.getFloat("brightness",-1f);repeat=preferences.getBoolean("repeat",false);equalizerPreset=preferences.getInt("equalizer",-1);
        audioDelay=preferences.getLong(mediaKey+"audioDelay",0);subtitleDelay=preferences.getLong(mediaKey+"subtitleDelay",0);
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);
        String target=intent.getStringExtra("uri");if(target==null&&PlaybackService.peek()!=null&&PlaybackService.peek().currentUri()!=null)target=PlaybackService.peek().currentUri().toString();
        if(target==null)return;Uri next=Uri.parse(target);if(!profile.contains(next)&&!Streams.remote(next))return;
        if(!next.equals(uri)){
            if(service!=null)service.detach(this);uri=next;player=null;hadMedia=false;thumbnailSaved=false;thumbnailPending=false;
            // A new episode starts on its own clock: the old one's elapsed time and length
            // would otherwise sit on the seek bar until the service reported the switch.
            position=0;lastDuration=0;started=false;ended=false;failed=false;previouslyPlaying=false;
            scrubbing=false;dragging=false;scrubTarget=0;scrubOrigin=0;handler.removeCallbacks(hide);
            readOptions();resetVideoLayout();buildUi();applyBrightness();
            if(service!=null&&foreground)service.attach(this,video,subtitles,this);
        }
        long start=intent.getBooleanExtra("resumeOnly",false)?-1:intent.getLongExtra("startMs",-1);
        requestedStart=start;firstOpen=true;
        if(foreground){PlaybackService.open(this,uri,start);firstOpen=false;}
    }
    private void resetVideoLayout(){windowWidth=windowHeight=videoWidth=videoHeight=visibleWidth=visibleHeight=0;videoReadyAt=0;}
    private void refreshService(){
        if(service==null||!foreground||refreshing)return;
        refreshing=true;
        try{
            if(!service.hasMedia()){player=null;updateResources();if(hadMedia)finish();return;}
            if(!uri.equals(service.currentUri())){
                // Playback rolled into the next episode on its own; the screen follows it.
                Uri moved=service.currentUri();
                if(moved!=null&&(profile.contains(moved)||Streams.remote(moved)))handler.post(()->{
                    if(isDestroyed()||service==null||moved.equals(uri)||!moved.equals(service.currentUri()))return;
                    onNewIntent(new Intent(this,PlayerActivity.class).putExtra("uri",moved.toString()).putExtra("resumeOnly",true));
                });
                return;
            }
            hadMedia=true;
            MediaPlayer current=service.player();if(current!=player){player=current;thumbnailSaved=false;resetVideoLayout();}
            started=service.started();failed=service.failed();ended=service.ended();pausedByUser=!service.wantsPlay();
            position=service.positionMs();lastDuration=service.durationMs();sleepDeadline=service.sleepDeadline();
            message.setText(service.status());toggle.setText(failed?"Retry":ended?"Replay":service.wantsPlay()?"Pause":"Play");
            if(!dragging){seek.setEnabled(player!=null&&player.isSeekable()&&lastDuration>0);seek.setProgress(lastDuration>0?(int)(position*10000/lastDuration):0);time.setText(Ui.time(position)+"  /  "+Ui.time(lastDuration));}
            boolean playing=service.isPlaying();if(playing!=previouslyPlaying){previouslyPlaying=playing;showControls(true);}
            updateResources();updatePip();
            if(started&&!thumbnailSaved&&!thumbnailPending&&position>5000&&service.isPlaying()&&!Streams.remote(uri))captureThumbnail();
        }finally{refreshing=false;}
    }
    private void buildUi() {
        root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK); setContentView(root);
        videoFrame = new FrameLayout(this); root.addView(videoFrame,new FrameLayout.LayoutParams(-1,-1));
        video = new SurfaceView(this); subtitles = new SurfaceView(this);
        android.view.SurfaceHolder.Callback surfaceCallback=new android.view.SurfaceHolder.Callback(){
            public void surfaceCreated(android.view.SurfaceHolder holder){if(service!=null&&foreground)service.attach(PlayerActivity.this,video,subtitles,PlayerActivity.this);}
            public void surfaceChanged(android.view.SurfaceHolder holder,int format,int width,int height){}
            public void surfaceDestroyed(android.view.SurfaceHolder holder){}
        };
        video.getHolder().addCallback(surfaceCallback);subtitles.getHolder().addCallback(surfaceCallback);
        subtitles.setZOrderMediaOverlay(true); subtitles.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        videoFrame.addView(video,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        videoFrame.addView(subtitles,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        View touch = new View(this); root.addView(touch,new FrameLayout.LayoutParams(-1,-1));
        GestureDetector gestures=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onSingleTapConfirmed(MotionEvent e){if(!locked)showControls(controls.getVisibility()!=View.VISIBLE);return true;}
            @Override public boolean onDoubleTap(MotionEvent e){if(!locked)jump(e.getX()<root.getWidth()/2f?-10000:10000);return true;}
        });
        touch.setOnTouchListener((v,event)->gestures.onTouchEvent(event));
        top = Ui.column(this); Ui.pad(top,16);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xf5111315,0xd9111315,0x00111315}));
        LinearLayout titleRow = Ui.row(this);
        titleRow.addView(Ui.iconButton(this,"back","Back to library",this::finish),new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        titleRow.addView(titleBlock(),new LinearLayout.LayoutParams(0,-2,1));
        if(tv){
            menuButton=Ui.iconButton(this,"menu","Player menu",this::tvMenu);
            titleRow.addView(menuButton,new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        }
        top.addView(titleRow);
        message = Ui.text(this,"Connecting to NAS…",13,Ui.ACCENT); top.addView(message);
        root.addView(top,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        controls = Ui.column(this); Ui.pad(controls,16);
        controls.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,tv?new int[]{0xd9111315,0x80111315,0x00111315}:new int[]{0xfa111315,0xee111315,0x66111315}));
        time = Ui.text(this,"0:00",13,Ui.INK); controls.addView(time);
        seek = new SeekBar(this); seek.setMax(10000); controls.addView(seek,new LinearLayout.LayoutParams(-1,Ui.dp(this,36)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { dragging=true; handler.removeCallbacks(hide); }
            public void onProgressChanged(SeekBar bar,int p,boolean user) { if (user && player!=null) time.setText(Ui.time(player.getLength()*p/10000)); }
            public void onStopTrackingTouch(SeekBar bar) { if(service!=null)service.seek(lastDuration*bar.getProgress()/10000); dragging=false; showControls(true); }
        });
        LinearLayout transport=Ui.row(this); transport.setGravity(Gravity.CENTER);
        Button back=Ui.button(this,"−10s",() -> jump(-10000)); back.setContentDescription("Seek back ten seconds");
        transport.addView(back,new LinearLayout.LayoutParams(Ui.dp(this,72),Ui.dp(this,48)));
        toggle=Ui.primary(this,"Pause",this::togglePlayback);
        LinearLayout.LayoutParams playSize=new LinearLayout.LayoutParams(Ui.dp(this,100),Ui.dp(this,48));playSize.setMargins(Ui.dp(this,12),Ui.dp(this,4),Ui.dp(this,12),Ui.dp(this,4));
        transport.addView(toggle,playSize);
        Button forward=Ui.button(this,"+10s",() -> jump(10000));forward.setContentDescription("Seek forward ten seconds");
        transport.addView(forward,new LinearLayout.LayoutParams(Ui.dp(this,72),Ui.dp(this,48)));
        if(!tv){
            controls.addView(transport);
            LinearLayout options=Ui.row(this); options.setGravity(Gravity.CENTER);
            for(Button b:new Button[]{Ui.button(this,"Audio",this::audioMenu),Ui.button(this,"Subtitles",this::subtitleMenu),Ui.button(this,"Options",this::more)}) {
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,Ui.dp(this,44),1);lp.setMargins(Ui.dp(this,3),Ui.dp(this,6),Ui.dp(this,3),0);options.addView(b,lp);
            }
            controls.addView(options);
        }
        root.addView(controls,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        buildEpisodes();
        unlock=Ui.button(this,"Hold to unlock",() -> toast("Hold this button to unlock controls"));unlock.setVisibility(View.GONE);
        unlock.setOnLongClickListener(v->{locked=false;showControls(true);return true;});
        FrameLayout.LayoutParams unlockSize=new FrameLayout.LayoutParams(-2,Ui.dp(this,48),Gravity.TOP|Gravity.END);unlockSize.setMargins(Ui.dp(this,16),Ui.dp(this,32),Ui.dp(this,16),0);root.addView(unlock,unlockSize);
        videoFrame.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if(l!=ol||t!=ot||r!=or||b!=ob){handler.removeCallbacks(updateVideoLayout);handler.post(updateVideoLayout);}
        });
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        root.setOnApplyWindowInsetsListener((v,insets) -> {
            int overscanX=Ui.overscanX(this),overscanY=Ui.overscanY(this);
            top.setPadding(Ui.dp(this,12)+overscanX+insets.getSystemWindowInsetLeft(),Ui.dp(this,12)+overscanY+insets.getSystemWindowInsetTop(),Ui.dp(this,12)+overscanX+insets.getSystemWindowInsetRight(),Ui.dp(this,12));
            controls.setPadding(Ui.dp(this,12)+overscanX+insets.getSystemWindowInsetLeft(),Ui.dp(this,8),Ui.dp(this,12)+overscanX+insets.getSystemWindowInsetRight(),Ui.dp(this,12)+overscanY+insets.getSystemWindowInsetBottom()); return insets;
        });
    }
    @Override protected void onStart(){
        handler.removeCallbacks(checkPipDismissal);
        android.util.Log.i("JPlay","player Activity start");
        super.onStart();if(uri==null)return;foreground=true;
        if(firstOpen){PlaybackService.open(this,uri,requestedStart);firstOpen=false;}
        else if(PlaybackService.peek()==null||!PlaybackService.peek().hasMedia()){finish();return;}
        bound=bindService(new Intent(this,PlaybackService.class),connection,Context.BIND_AUTO_CREATE);
    }
    private void startPlayback(){if(service!=null)service.restartPlaying(position);showControls(true);}
    private void play(){if(service!=null)service.play();showControls(true);}
    private void pause(){if(service!=null)service.pause();showControls(true);}
    private void jump(long delta){if(service!=null)service.jump(delta);showControls(true);}
    /** What is playing, what it belongs to, and when it aired: the seek screen answers all three. */
    private View titleBlock(){
        LibraryStore.Item item=LibraryStore.get(this).item(uri);org.json.JSONObject m=item.metadata;
        LinearLayout words=Ui.column(this);words.setPadding(Ui.dp(this,12),Ui.dp(this,4),Ui.dp(this,12),Ui.dp(this,4));
        TextView title=Ui.heading(this,Streams.title(uri,item.title()),tv?21:18);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(title);
        java.util.ArrayList<String> facts=new java.util.ArrayList<>();
        String show=m.optString("tvmazeShowName").trim();if(!show.isEmpty())facts.add(show);
        String aired=airDate(m.optString("tvmazeDate").trim());if(!aired.isEmpty())facts.add(aired);
        String rating=m.optString("tvmazeRating").trim();if(!rating.isEmpty()&&!"null".equals(rating))facts.add(rating+" \u2605");
        if(aired.isEmpty()&&!item.year().isEmpty())facts.add(item.year());
        if(item.duration>0)facts.add(item.duration/60000+" min");
        if(!item.format().isEmpty())facts.add(item.format());
        if(!facts.isEmpty()){TextView line=Ui.text(this,android.text.TextUtils.join("  \u00b7  ",facts),tv?13:12,Ui.MUTED);line.setMaxLines(1);line.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(line);}
        String plot=m.optString("tvmazeDescription").trim();
        if(tv&&!plot.isEmpty()){TextView note=Ui.text(this,plot,13,Ui.MUTED);note.setMaxLines(2);note.setEllipsize(android.text.TextUtils.TruncateAt.END);note.setAlpha(.85f);words.addView(note);}
        return words;
    }
    private String airDate(String iso){
        if(iso.length()<10)return "";
        try{return java.time.LocalDate.parse(iso.substring(0,10)).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"));}
        catch(Exception e){return iso;}
    }
    /**
     * The episodes beside this one, one press of DOWN away. The strip owns its own cursor
     * rather than Android focus, so left and right keep meaning "next episode" whatever the
     * seek bar is doing underneath.
     */
    private void buildEpisodes(){
        siblings.clear();episodeCards.clear();episodes=null;episodeIndex=-1;episodesVisible=false;
        if(!tv||uri==null||Streams.remote(uri))return;
        String address=uri.toString();int cut=address.lastIndexOf('/');if(cut<=0)return;
        siblings.addAll(LibraryStore.get(this).folder(Uri.parse(address.substring(0,cut))));
        for(int n=0;n<siblings.size();n++)if(siblings.get(n).uri.equals(uri))episodeIndex=n;
        if(episodeIndex<0||siblings.size()<2){siblings.clear();return;}
        episodeChoice=episodeIndex;
        episodes=Ui.column(this);episodes.setVisibility(View.GONE);
        episodes.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,new int[]{0xf2111315,0xcc111315,0x00111315}));
        episodes.setPadding(Ui.dp(this,16)+Ui.overscanX(this),Ui.dp(this,18),Ui.dp(this,16)+Ui.overscanX(this),Ui.dp(this,14)+Ui.overscanY(this));
        episodes.addView(Ui.label(this,"EPISODES"));
        episodeScroller=new android.widget.HorizontalScrollView(this);episodeScroller.setHorizontalScrollBarEnabled(false);episodeScroller.setClipToPadding(false);
        episodeStrip=Ui.row(this);episodeStrip.setGravity(Gravity.TOP);
        for(int n=0;n<siblings.size();n++){
            View card=episodeCard(siblings.get(n));episodeCards.add(card);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(Ui.dp(this,190),-2);lp.rightMargin=Ui.dp(this,14);
            episodeStrip.addView(card,lp);
        }
        episodeScroller.addView(episodeStrip);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=Ui.dp(this,10);episodes.addView(episodeScroller,sp);
        root.addView(episodes,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
    }
    private View episodeCard(LibraryStore.Item item){
        LinearLayout card=Ui.column(this);card.setPadding(Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,4));
        ArtworkView art=new ArtworkView(this);art.bind(item);card.addView(art,new LinearLayout.LayoutParams(-1,Ui.dp(this,105)));
        TextView name=Ui.heading(this,item.title(),13);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.topMargin=Ui.dp(this,8);card.addView(name,np);
        String line=item.summary();TextView sub=Ui.text(this,line.isEmpty()?"Episode":line,11,Ui.MUTED);sub.setMaxLines(1);card.addView(sub);
        return card;
    }
    private void highlightEpisode(){
        for(int n=0;n<episodeCards.size();n++){
            View card=episodeCards.get(n);boolean chosen=n==episodeChoice;
            GradientDrawable ring=Ui.background(n==episodeIndex?0x26f38bcb:Color.TRANSPARENT,14,this);
            if(chosen)ring.setStroke(Ui.dp(this,3),Ui.ACCENT);
            card.setBackground(ring);card.setAlpha(chosen?1f:.72f);
        }
        View card=episodeCards.get(episodeChoice);
        int centre=card.getLeft()-(episodeScroller.getWidth()-Ui.dp(this,190))/2;
        episodeScroller.post(()->episodeScroller.smoothScrollTo(Math.max(0,centre),0));
    }
    private void moveEpisode(int step){
        int next=episodeChoice+step;if(next<0||next>=siblings.size())return;
        episodeChoice=next;highlightEpisode();
        handler.removeCallbacks(hideEpisodes);handler.postDelayed(hideEpisodes,12000);
    }
    private void openEpisode(){
        LibraryStore.Item item=siblings.get(episodeChoice);showEpisodes(false);
        if(item.uri.equals(uri))return;
        startActivity(new Intent(this,PlayerActivity.class).putExtra("uri",item.uri.toString()).putExtra("startMs",-1L));
    }
    private void showEpisodes(boolean show){
        if(episodes==null)return;
        episodesVisible=show&&!locked&&!isInPictureInPictureMode();
        episodes.setVisibility(episodesVisible?View.VISIBLE:View.GONE);
        handler.removeCallbacks(hideEpisodes);
        if(!episodesVisible)return;
        episodeChoice=episodeIndex;highlightEpisode();showControls(false);
        handler.postDelayed(hideEpisodes,12000);
    }
    private void showControls(boolean show) {
        handler.removeCallbacks(hide);
        if(isInPictureInPictureMode()||locked) show=false;
        controls.setVisibility(show?View.VISIBLE:View.GONE); top.setVisibility(show?View.VISIBLE:View.GONE);
        unlock.setVisibility(locked&&!isInPictureInPictureMode()?View.VISIBLE:View.GONE);
        // A remote cannot point at a control, so the cursor lands on the seek bar, or on
        // play/pause when the media cannot be sought.
        if(show && tv && !controls.hasFocus() && !top.hasFocus()){ if(!(seek.isEnabled()&&seek.requestFocus())) menuButton.requestFocus(); }
        if(show && !dragging && !failed && menuDepth==0) handler.postDelayed(hide,tv?8000:4500);
    }
    /**
     * Remote transport. Arrow and media keys scrub the seek bar with an accelerating step and
     * commit on key-up, because a SeekBar driven by D-pad never fires onStopTrackingTouch and
     * would move its thumb without ever seeking.
     */
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(!tv||locked||controls==null) return super.dispatchKeyEvent(event);
        int code=event.getKeyCode();
        boolean down=event.getAction()==KeyEvent.ACTION_DOWN;
        if(episodesVisible){
            if(!down)return true;
            switch(code){
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS: moveEpisode(-1); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_MEDIA_NEXT: moveEpisode(1); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: openEpisode(); return true;
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_BACK: showEpisodes(false); return true;
                default: showEpisodes(false);
            }
        }
        boolean visible=controls.getVisibility()==View.VISIBLE;
        boolean onSeek=seek.hasFocus();
        switch(code){
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_LEFT: {
                boolean media=code==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD||code==KeyEvent.KEYCODE_MEDIA_REWIND;
                // With the controls up and the cursor on a button, arrows still move focus.
                if(!media&&visible&&!onSeek&&!scrubbing) break;
                boolean forward=code==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD||code==KeyEvent.KEYCODE_DPAD_RIGHT;
                if(down) scrub(forward,event.getRepeatCount()); else commitScrub();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if(!visible||onSeek){ if(down&&event.getRepeatCount()==0) togglePlayback(); return true; }
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_HEADSETHOOK:
                if(down&&event.getRepeatCount()==0) togglePlayback(); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if(down&&service!=null&&!service.isPlaying()) togglePlayback(); else if(down) showControls(true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if(down&&service!=null&&service.isPlaying()) togglePlayback(); else if(down) showControls(true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if(down){PlaybackService.stop(this);finish();} return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT: if(down) scrub(true,0); else commitScrub(); return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: if(down) scrub(false,0); else commitScrub(); return true;
            case KeyEvent.KEYCODE_MENU:
                if(down&&menuDepth==0) tvMenu(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if(down&&episodes!=null){ showEpisodes(true); return true; }
                if(!visible){ if(down) showControls(true); return true; }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if(!visible){ if(down) showControls(true); return true; }
                break;
        }
        return super.dispatchKeyEvent(event);
    }
    /** Held keys accelerate: ten seconds, then thirty, a minute, five minutes. */
    private void scrub(boolean forward,int repeat){
        if(service==null) return;
        if(lastDuration<=0||player==null||!player.isSeekable()){ showControls(true); return; }
        if(!scrubbing){ scrubbing=true; dragging=true; scrubTarget=position; scrubOrigin=position; }
        long step=repeat<2?10000:repeat<6?30000:repeat<12?60000:300000;
        scrubTarget=Math.max(0,Math.min(lastDuration,scrubTarget+(forward?step:-step)));
        seek.setProgress((int)(scrubTarget*10000/lastDuration));
        long moved=scrubTarget-scrubOrigin;
        time.setText(Ui.time(scrubTarget)+"  /  "+Ui.time(lastDuration)+"     "+(moved<0?"\u2212":"+")+Ui.time(Math.abs(moved)));
        showControls(true);
        // Commit even if the key-up is lost, so a scrub can never stick.
        handler.removeCallbacks(commitScrubSoon);
        handler.postDelayed(commitScrubSoon,700);
    }
    private void commitScrub(){
        if(!scrubbing) return;
        handler.removeCallbacks(commitScrubSoon);
        scrubbing=false; dragging=false; position=scrubTarget;
        if(service!=null) service.seek(scrubTarget);
        showControls(true);
    }
    private void togglePlayback(){
        if(failed||ended){pausedByUser=false; if(ended)position=0; startPlayback(); return;}
        if(player!=null&&player.isPlaying()) pause(); else {pausedByUser=false; play();}
    }
    private void tracks(boolean subs) {
        if(player==null)return; handler.removeCallbacks(hide);
        MediaPlayer.TrackDescription[] tracks=subs?player.getSpuTracks():player.getAudioTracks();
        if(tracks==null || tracks.length==0) { info(subs?"Subtitles":"Audio",subs?"No embedded subtitle tracks. You can add an external subtitle file.":"This file has no audio tracks."); return; }
        String[] names=new String[tracks.length]; int selected=-1;
        for(int i=0;i<tracks.length;i++) { names[i]=tracks[i].name; if(tracks[i].id==(subs?player.getSpuTrack():player.getAudioTrack()))selected=i; }
        showDialog(new AlertDialog.Builder(this).setTitle(subs?"Subtitle track":"Audio track").setSingleChoiceItems(names,selected,(dialog,which) -> {
            if(player!=null) {
                boolean accepted=subs?player.setSpuTrack(tracks[which].id):player.setAudioTrack(tracks[which].id);
                if(accepted)preferences.edit().putInt(mediaKey+(subs?"subtitle":"audio"),tracks[which].id).apply();else toast("This track could not be selected");
            }
            dialog.dismiss(); showControls(true);
        }).setNegativeButton("Close",null).create());
    }
    private void more() {
        menu("Player options",new String[]{"Playback  ·  speed, chapters, repeat","Display  ·  fit, zoom, brightness","Session  ·  sleep, PiP, screen lock","Decoder  ·  "+(software?"Software":"Automatic"),"Media information","Crash reports"},
            new Runnable[]{this::playbackMenu,this::displayMenu,this::sessionMenu,this::decoderMenu,this::mediaInformation,()->CrashReports.get(this).showSettings(this)});
    }
    private void playbackMenu() {
        menu("Playback",new String[]{String.format(Locale.ROOT,"Speed  ·  %.2f×",rate),"Go to time","Chapters","Video track","Repeat this video  ·  "+(repeat?"On":"Off"),"Restart from beginning"},
            new Runnable[]{()->slider("Playback speed",55,Math.round((rate-.25f)/.05f),v->String.format(Locale.ROOT,"%.2f×",.25f+v*.05f),v->{
                if(player==null)return;rate=.25f+v*.05f;player.setRate(rate);
                if(Math.abs(player.getRate()-rate)>.03f){toast("This playback speed is not supported");rate=player.getRate();}
                preferences.edit().putFloat("rate",rate).apply();updateSession();
            }),this::goToTime,this::chapters,this::videoTracks,()->{repeat=!repeat;preferences.edit().putBoolean("repeat",repeat).apply();toast(repeat?"Repeat enabled":"Repeat disabled");},()->{position=0;pausedByUser=false;startPlayback();}});
    }
    private void audioMenu() {
        menu("Audio",new String[]{"Audio track","Audio delay  ·  "+audioDelay/1000+" ms","Volume","Equalizer"},new Runnable[]{()->tracks(false),()->delay(false),()->{
            int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            slider("Volume",max,audio.getStreamVolume(AudioManager.STREAM_MUSIC),v->Math.round(v*100f/max)+"%",v->audio.setStreamVolume(AudioManager.STREAM_MUSIC,v,0));
        },this::equalizer});
    }
    private void subtitleMenu() {
        menu("Subtitles",new String[]{"Subtitle track","Add subtitle file from phone","Remove external subtitle file","Subtitle delay  ·  "+subtitleDelay/1000+" ms","Turn subtitles off"},
            new Runnable[]{()->tracks(true),this::chooseSubtitles,()->{
                String path=preferences.getString(mediaKey+"external","");preferences.edit().remove(mediaKey+"external").apply();
                if(!path.isEmpty())disk.execute(()->new File(path).delete());
                if(player!=null)player.setSpuTrack(-1);toast("External subtitles removed for next playback");
            },()->delay(true),()->{if(player!=null&&player.setSpuTrack(-1))preferences.edit().putInt(mediaKey+"subtitle",-1).apply();else toast("No subtitle track is active");}});
    }
    private void displayMenu() {
        menu("Display",new String[]{"Aspect and fit  ·  "+aspect,"Zoom  ·  "+Math.round(zoom*100)+"%","Brightness","Follow system brightness","Rotate screen"},
            new Runnable[]{()->{
                String[] values={"fit","crop","stretch","16:9","4:3","21:9"};
                menu("Aspect and fit",new String[]{"Fit original aspect","Fill screen / crop","Stretch to screen","16:9","4:3","21:9"},new Runnable[]{()->setAspect(values[0]),()->setAspect(values[1]),()->setAspect(values[2]),()->setAspect(values[3]),()->setAspect(values[4]),()->setAspect(values[5])});
            },()->slider("Zoom",150,Math.round((zoom-1)*100),v->(100+v)+"%",v->{zoom=1+v/100f;preferences.edit().putFloat("zoom",zoom).apply();layoutVideo();}),
            ()->slider("Brightness",95,brightness<0?45:Math.round(brightness*100)-5,v->(v+5)+"%",v->{brightness=(v+5)/100f;preferences.edit().putFloat("brightness",brightness).apply();applyBrightness();}),
            ()->{brightness=-1;preferences.edit().putFloat("brightness",-1).apply();applyBrightness();},
            ()->setRequestedOrientation(getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE?ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)});
    }
    private void sessionMenu() {
        String remaining=sleepDeadline>0?"  ·  "+Math.max(0,(sleepDeadline-SystemClock.elapsedRealtime()+59999)/60000)+" min":"  ·  Off";
        menu("Session",new String[]{"Sleep timer"+remaining,"Picture in picture","Automatic PiP  ·  "+(preferences.getBoolean("autoPip",true)?"On":"Off"),"Lock screen controls","Stop playback"},
            new Runnable[]{this::sleepMenu,this::pip,()->{boolean value=!preferences.getBoolean("autoPip",true);preferences.edit().putBoolean("autoPip",value).apply();updatePip();toast(value?"Automatic PiP enabled":"Automatic PiP disabled");},()->{locked=true;showControls(false);},()->PlaybackService.stop(this)});
    }
    private void decoderMenu() {
        menu("Decoder for this video",new String[]{"Automatic  ·  hardware when supported","Software  ·  avoids hardware decoding; higher battery use"},new Runnable[]{()->setDecoder(false),()->setDecoder(true)});
    }
    private void setDecoder(boolean value){if(service!=null)position=service.positionMs();software=value;preferences.edit().putBoolean(mediaKey+"software",value).apply();if(service!=null)service.restart(position);}
    private void setAspect(String value){aspect=value;preferences.edit().putString("aspect",value).apply();layoutVideo();}
    private void sleepMenu() {
        int[] minutes={0,15,30,45,60,90,120};String[] names={"Off","15 minutes","30 minutes","45 minutes","60 minutes","90 minutes","120 minutes"};Runnable[] actions=new Runnable[minutes.length];
        for(int i=0;i<minutes.length;i++){final int duration=minutes[i];actions[i]=()->{
            if(service!=null){service.setSleepMinutes(duration);sleepDeadline=service.sleepDeadline();}toast(duration==0?"Sleep timer off":"Playback will pause in "+duration+" minutes");
        };}menu("Sleep timer",names,actions);
    }
    private void chapters() {
        if(player==null)return;MediaPlayer.Chapter[] list=player.getChapters(Math.max(0,player.getTitle()));
        if(list==null||list.length==0){info("Chapters","This file has no chapter markers.");return;}
        String[] names=new String[list.length];Runnable[] actions=new Runnable[list.length];
        for(int i=0;i<list.length;i++){final int index=i;names[i]=Ui.time(list[i].timeOffset)+"  ·  "+(list[i].name==null||list[i].name.isEmpty()?"Chapter "+(i+1):list[i].name);actions[i]=()->{if(player!=null)player.setChapter(index);};}
        menu("Chapters",names,actions);
    }
    private void videoTracks() {
        if(player==null)return;MediaPlayer.TrackDescription[] tracks=player.getVideoTracks();
        if(tracks==null||tracks.length==0){info("Video track","No selectable video tracks.");return;}
        String[] names=new String[tracks.length];Runnable[] actions=new Runnable[tracks.length];
        for(int i=0;i<tracks.length;i++){final int index=i;names[i]=tracks[i].name;actions[i]=()->{if(service!=null&&!service.selectVideoTrack(tracks[index].id))toast("This video track is unavailable");};}
        menu("Video track",names,actions);
    }
    private void equalizer() {
        int count=MediaPlayer.Equalizer.getPresetCount();String[] names=new String[count+1];Runnable[] actions=new Runnable[count+1];names[0]="Off";actions[0]=()->setEqualizer(-1);
        for(int i=0;i<count;i++){final int index=i;names[i+1]=MediaPlayer.Equalizer.getPresetName(i);actions[i+1]=()->setEqualizer(index);}
        menu("Equalizer",names,actions);
    }
    private void setEqualizer(int preset) {
        if(player==null)return;
        if(player.setEqualizer(preset<0?null:MediaPlayer.Equalizer.createFromPreset(preset))){equalizerPreset=preset;preferences.edit().putInt("equalizer",preset).apply();}
        else toast("Equalizer is unavailable for this output");
    }
    private void delay(boolean subs) {
        EditText input=new EditText(this);input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED);input.setText(Long.toString((subs?subtitleDelay:audioDelay)/1000));
        LinearLayout body=Ui.column(this);Ui.pad(body,20);body.addView(Ui.text(this,"Milliseconds. Positive values play later; negative values play earlier. Saved for this video.",14,Ui.MUTED));body.addView(input);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(subs?"Subtitle delay":"Audio delay").setView(body).setPositiveButton("Apply",null).setNeutralButton("Reset",(d,w)->applyDelay(subs,0)).setNegativeButton("Cancel",null).create();
        showDialog(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{long ms=Long.parseLong(input.getText().toString());if(ms < -60000 || ms > 60000)throw new NumberFormatException();applyDelay(subs,ms*1000);dialog.dismiss();}
            catch(NumberFormatException e){input.setError("Use a value between −60000 and 60000 ms");}
        });
    }
    private void applyDelay(boolean subs,long micros) {
        if(player==null)return;boolean accepted=subs?player.setSpuDelay(micros):player.setAudioDelay(micros);
        if(!accepted){toast(subs?"No active subtitle track supports this delay":"No active audio output supports this delay");return;}
        if(subs)subtitleDelay=micros;else audioDelay=micros;
        preferences.edit().putLong(mediaKey+(subs?"subtitleDelay":"audioDelay"),micros).apply();
    }
    private void goToTime() {
        if(player==null||!player.isSeekable()){toast("This stream cannot seek");return;}
        EditText input=new EditText(this);input.setSingleLine();input.setHint("hh:mm:ss or mm:ss");input.setText(Ui.time(position));Ui.pad(input,20);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Go to time").setView(input).setPositiveButton("Seek",null).setNegativeButton("Cancel",null).create();
        showDialog(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                String value=input.getText().toString().trim();if(!value.matches("\\d{1,3}(:[0-5]\\d){1,2}"))throw new NumberFormatException();
                long seconds=0;for(String part:value.split(":"))seconds=seconds*60+Long.parseLong(part);
                if(player==null||seconds*1000>player.getLength())throw new NumberFormatException();if(service!=null)service.seek(seconds*1000);dialog.dismiss();
            }catch(NumberFormatException e){input.setError("Enter a time within this video");}
        });
    }
    /**
     * TV player menu: one sheet down the side of the screen, so the playing picture keeps
     * the rest of the frame. Every entry runs the same action as the phone's button rows.
     */
    private void tvMenu(){
        String[] names={"Audio","Subtitles","Playback  ·  speed, chapters, repeat","Display  ·  fit, zoom, brightness",
            "Session  ·  sleep, PiP, screen lock","Decoder  ·  "+(software?"Software":"Automatic"),"Media information","Crash reports"};
        Runnable[] actions={this::audioMenu,this::subtitleMenu,this::playbackMenu,this::displayMenu,
            this::sessionMenu,this::decoderMenu,this::mediaInformation,()->CrashReports.get(this).showSettings(this)};
        Dialog sheet=new Dialog(this);sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout body=Ui.column(this);body.setBackgroundColor(0xf5140e1c);
        body.setPadding(Ui.dp(this,20),Ui.dp(this,28),Ui.dp(this,20),Ui.dp(this,20));
        TextView heading=Ui.heading(this,"Player",20);body.addView(heading);
        for(int i=0;i<names.length;i++){
            final Runnable action=actions[i];
            Button entry=Ui.button(this,names[i],()->{sheet.dismiss();action.run();});
            entry.setGravity(Gravity.CENTER_VERTICAL);entry.setPadding(Ui.dp(this,14),0,Ui.dp(this,14),0);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Ui.dp(this,52));lp.topMargin=Ui.dp(this,8);
            body.addView(entry,lp);
            if(i==0)entry.post(entry::requestFocus);
        }
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(body);
        scroll.setBackgroundColor(0xf5140e1c);sheet.setContentView(scroll);
        Window window=sheet.getWindow();
        if(window!=null){
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            window.setGravity(Gravity.END);window.setLayout(Ui.dp(this,400),-1);
            window.setDimAmount(.25f);
        }
        menuDepth++;handler.removeCallbacks(hide);
        sheet.setOnDismissListener(d->{menuDepth=Math.max(0,menuDepth-1);showControls(true);});
        sheet.show();
    }
    private void menu(String title,String[] names,Runnable[] actions) {
        showDialog(new AlertDialog.Builder(this).setTitle(title).setItems(names,(d,index)->actions[index].run()).setNegativeButton("Close",null).create());
    }
    private void info(String title,String text){showDialog(new AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("Done",null).create());}
    private void showDialog(AlertDialog dialog) {
        menuDepth++;handler.removeCallbacks(hide);
        dialog.setOnDismissListener(d->{menuDepth=Math.max(0,menuDepth-1);showControls(true);});dialog.show();
    }
    private void slider(String title,int max,int current,java.util.function.IntFunction<String> format,java.util.function.IntConsumer apply) {
        LinearLayout body=Ui.column(this);Ui.pad(body,20);TextView value=Ui.heading(this,format.apply(current),24);body.addView(value);
        SeekBar bar=new SeekBar(this);bar.setMax(max);bar.setProgress(Math.max(0,Math.min(max,current)));body.addView(bar,new LinearLayout.LayoutParams(-1,Ui.dp(this,56)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}public void onProgressChanged(SeekBar b,int p,boolean user){value.setText(format.apply(p));}});
        showDialog(new AlertDialog.Builder(this).setTitle(title).setView(body).setPositiveButton("Apply",(d,w)->apply.accept(bar.getProgress())).setNegativeButton("Cancel",null).create());
    }
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
    private void mediaInformation() {
        if(player==null)return;IMedia media=player.getMedia();StringBuilder text=new StringBuilder();
        try {
            text.append("Duration  ").append(Ui.time(player.getLength())).append("\nVideo  ").append(visibleWidth).append(" × ").append(visibleHeight).append("\nDecoder  ").append(software?"Software":"Automatic").append("\nBuffer  ").append(profile.bufferMs/1000).append(" seconds\n");
            if(media!=null)for(int i=0;i<media.getTrackCount();i++){
                IMedia.Track track=media.getTrack(i);if(track==null)continue;text.append("\n").append(track.type==IMedia.Track.Type.Video?"Video":track.type==IMedia.Track.Type.Audio?"Audio":"Subtitle").append("  ").append(track.codec);
                if(track.language!=null)text.append(" · ").append(track.language);
                if(track instanceof IMedia.VideoTrack){IMedia.VideoTrack t=(IMedia.VideoTrack)track;if(t.frameRateDen>0)text.append(String.format(Locale.ROOT," · %.3f fps",(double)t.frameRateNum/t.frameRateDen));}
                if(track instanceof IMedia.AudioTrack){IMedia.AudioTrack t=(IMedia.AudioTrack)track;text.append(" · ").append(t.channels).append(" channels · ").append(t.rate).append(" Hz");}
            }
            text.append("\n\n").append(diagnostics().replace(" ","\n"));
        }finally{if(media!=null)media.release();}
        info("Media information",text.toString());
    }
    private String diagnostics(){return service==null?"Player stopped":service.diagnostics();}
    private void updateSession(){if(service!=null)service.optionsChanged();}
    private void captureThumbnail() {
        if(player==null||!foreground||videoReadyAt==0||SystemClock.elapsedRealtime()-videoReadyAt<2000||!video.getHolder().getSurface().isValid()||visibleWidth<=0||visibleHeight<=0)return;
        IMedia media=player.getMedia();
        try{IMedia.Stats stats=media==null?null:media.getStats();if(stats==null||stats.displayedPictures<12)return;}
        finally{if(media!=null)media.release();}
        final MediaPlayer capturedPlayer=player;
        thumbnailPending=true;final Uri target=uri;
        int height=Math.max(90,Math.min(640,Math.round(480f*visibleHeight/visibleWidth)));
        Bitmap bitmap=Bitmap.createBitmap(480,height,Bitmap.Config.ARGB_8888);
        try {PixelCopy.request(video,bitmap,result->{
            thumbnailPending=false;
            if(result==PixelCopy.SUCCESS&&!isDestroyed()&&foreground&&player==capturedPlayer&&target.equals(uri)){
                if(target.equals(uri))thumbnailSaved=true;disk.execute(()->{try{LibraryStore.get(this).saveThumbnail(target,bitmap);}finally{bitmap.recycle();}});
            }else bitmap.recycle();
        },handler);}catch(IllegalArgumentException e){thumbnailPending=false;bitmap.recycle();}
    }
    private void applyBrightness(){WindowManager.LayoutParams p=getWindow().getAttributes();p.screenBrightness=brightness;getWindow().setAttributes(p);}
    private void updateResources(){
        if(foreground&&service!=null&&service.isPlaying())getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void chooseSubtitles() {
        choosingFile=true;wasPlayingBeforePicker=player!=null&&player.isPlaying();pausedByUser=true;
        if(service!=null)service.pause();updatePip();
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try{startActivityForResult(intent,41);}catch(android.content.ActivityNotFoundException e){choosingFile=false;if(wasPlayingBeforePicker)play();updatePip();toast("No document picker is installed");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);if(request!=41)return;
        choosingFile=false;if(wasPlayingBeforePicker){PlaybackService running=PlaybackService.peek();if(running!=null)running.play();}updatePip();
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        final Uri document=data.getData(),mediaUri=uri;final String subtitleKey=mediaKey;
        disk.execute(()->{
            try {
                String name="";
                try(Cursor c=getContentResolver().query(document,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}
                String extension=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
                if(!extension.matches("srt|ass|ssa|vtt|sub|ttml|smi"))throw new IllegalArgumentException("Choose SRT, ASS, SSA, VTT, SUB, TTML or SMI subtitles");
                File folder=new File(getFilesDir(),"subtitles");if(!folder.isDirectory()&&!folder.mkdirs())throw new java.io.IOException();
                File file=new File(folder,LibraryStore.key(mediaUri)+"."+extension);
                try(InputStream input=getContentResolver().openInputStream(document)){
                    if(input==null)throw new java.io.IOException();byte[] bytes=CrashReports.readBounded(input,10*1024*1024);
                    android.util.AtomicFile atomic=new android.util.AtomicFile(file);java.io.FileOutputStream out=null;
                    try{out=atomic.startWrite();out.write(bytes);atomic.finishWrite(out);}catch(Exception e){if(out!=null)atomic.failWrite(out);throw e;}
                }
                preferences.edit().putString(subtitleKey+"external",file.getAbsolutePath()).remove(subtitleKey+"subtitle").apply();
                runOnUiThread(()->{
                    if(isDestroyed())return;
                    if(player!=null&&started&&mediaUri.equals(uri)){boolean ok=player.addSlave(IMedia.Slave.Type.Subtitle,file.getAbsolutePath(),true);toast(ok?"External subtitles added":"The player could not load these subtitles");}
                    else toast("Subtitle file saved for this video");
                });
            }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())toast(e instanceof IllegalArgumentException?"Choose a supported subtitle file under 10 MB":"Could not read this subtitle file");});}
        });
    }
    private android.app.RemoteAction pipAction(int icon,String label,String command){
        return new android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this,icon),label,label,PlaybackService.action(this,command));
    }
    private PictureInPictureParams pipParams(){
        PictureInPictureParams.Builder b=new PictureInPictureParams.Builder();
        double ratio=visibleHeight>0?(double)visibleWidth/visibleHeight:16.0/9;ratio=Math.max(.42,Math.min(2.39,ratio));
        b.setAspectRatio(new Rational((int)(ratio*1000),1000));
        android.graphics.Rect bounds=new android.graphics.Rect();if(video.getGlobalVisibleRect(bounds)&&!bounds.isEmpty())b.setSourceRectHint(bounds);
        boolean playing=service!=null&&service.wantsPlay()&&!service.failed()&&!service.ended();
        b.setActions(java.util.Arrays.asList(pipAction(android.R.drawable.ic_media_rew,"Back 10 seconds",PlaybackService.BACK),
            pipAction(playing?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play,playing?"Pause":"Play",playing?PlaybackService.PAUSE:PlaybackService.PLAY),
            pipAction(android.R.drawable.ic_media_ff,"Forward 10 seconds",PlaybackService.NEXT)));
        if(Build.VERSION.SDK_INT>=31)b.setAutoEnterEnabled(!choosingFile&&!isFinishing()&&preferences.getBoolean("autoPip",true)&&playing).setSeamlessResizeEnabled(true);
        if(Build.VERSION.SDK_INT>=33)b.setCloseAction(pipAction(android.R.drawable.ic_menu_close_clear_cancel,"Stop playback",PlaybackService.STOP)).setTitle(service!=null?service.mediaTitle():"Just Play");
        return b.build();
    }
    private void updatePip(){if(uri!=null&&getPackageManager().hasSystemFeature("android.software.picture_in_picture"))try{setPictureInPictureParams(pipParams());}catch(IllegalStateException ignored){}}
    private void pip(){
        if(service==null||!service.hasMedia()||!getPackageManager().hasSystemFeature("android.software.picture_in_picture")){toast("Picture in picture is unavailable");return;}
        try{if(!enterPictureInPictureMode(pipParams()))toast("Picture in picture is disabled in Android settings");}catch(IllegalStateException e){toast("Picture in picture is unavailable now");}
    }
    @Override protected void onUserLeaveHint(){if(Build.VERSION.SDK_INT<31&&!choosingFile&&preferences!=null&&preferences.getBoolean("autoPip",true)&&service!=null&&service.isPlaying())pip();}
    @Override public void onPictureInPictureModeChanged(boolean pip,Configuration c){
        super.onPictureInPictureModeChanged(pip,c);android.util.Log.i("JPlay","picture in picture="+pip);showControls(!pip);handler.post(updateVideoLayout);
        handler.removeCallbacks(checkPipDismissal);
        if(Build.VERSION.SDK_INT>=33&&!pip&&!foreground)handler.postDelayed(checkPipDismissal,300);
    }
    @Override public void onNewVideoLayout(IVLCVout vout,int w,int h,int vw,int vh,int sn,int sd) {
        if(player==null||vout!=player.getVLCVout())return;
        if(videoReadyAt==0)videoReadyAt=SystemClock.elapsedRealtime();
        videoWidth=w; videoHeight=h; visibleWidth=vw; visibleHeight=vh; sarNum=sn; sarDen=sd; layoutVideo();updatePip();
    }
    private void layoutVideo() {
        if(player==null || videoFrame.getWidth()==0 || videoFrame.getHeight()==0)return;
        int w=videoFrame.getWidth(),h=videoFrame.getHeight();
        if(w!=windowWidth||h!=windowHeight){windowWidth=w;windowHeight=h;player.getVLCVout().setWindowSize(w,h);android.util.Log.i("JPlay","video window="+w+"x"+h+" pip="+isInPictureInPictureMode());}
        // LibVLC owns the native buffer geometry. Resize the presentation view only.
        if(isInPictureInPictureMode()){
            player.setAspectRatio(null);player.setScale(0);
            if(video.getLayoutParams().width!=-1||video.getLayoutParams().height!=-1){
                video.setLayoutParams(new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
                subtitles.setLayoutParams(new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
            }
            return;
        }
        if(visibleWidth>0 && visibleHeight>0) {
            double ratio=(double)visibleWidth/visibleHeight*(sarDen>0&&sarNum>0?(double)sarNum/sarDen:1);
            if(aspect.contains(":")){String[] parts=aspect.split(":");ratio=Double.parseDouble(parts[0])/Double.parseDouble(parts[1]);player.setAspectRatio(aspect);}
            else player.setAspectRatio(aspect.equals("stretch")?w+":"+h:null);
            if(!aspect.equals("stretch")) {
                boolean widthLimited=w/(double)h<=ratio;
                if(aspect.equals("crop"))widthLimited=!widthLimited;
                if(widthLimited)h=(int)Math.round(w/ratio);else w=(int)Math.round(h*ratio);
            }
            w=Math.round(w*zoom);h=Math.round(h*zoom);
            int sw=(int)Math.ceil(w*(double)videoWidth/visibleWidth), sh=(int)Math.ceil(h*(double)videoHeight/visibleHeight);
            if(video.getLayoutParams().width!=sw||video.getLayoutParams().height!=sh){
                video.setLayoutParams(new FrameLayout.LayoutParams(sw,sh,Gravity.CENTER));
                subtitles.setLayoutParams(new FrameLayout.LayoutParams(sw,sh,Gravity.CENTER));
            }
        }
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("locked",locked);super.onSaveInstanceState(state);}
    @Override protected void onStop(){
        android.util.Log.i("JPlay","player Activity stop finishing="+isFinishing()+" pip="+isInPictureInPictureMode());
        videoReadyAt=0;
        foreground=false;handler.removeCallbacks(hide);
        if(service!=null){service.removeListener(serviceChanged);service.detach(this);}
        if(bound){unbindService(connection);bound=false;}service=null;player=null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);super.onStop();
    }
    @Override public void finish(){
        if(Build.VERSION.SDK_INT>=31&&uri!=null&&getPackageManager().hasSystemFeature("android.software.picture_in_picture"))setPictureInPictureParams(new PictureInPictureParams.Builder().setAutoEnterEnabled(false).build());
        super.finish();
    }
    @Override public void onBackPressed(){
        if(locked){toast("Hold the unlock button to leave playback");return;}
        if(tv&&controls.getVisibility()==View.VISIBLE&&player!=null&&player.isPlaying()){showControls(false);return;}
        super.onBackPressed();
    }
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);disk.shutdown();super.onDestroy();}
}
