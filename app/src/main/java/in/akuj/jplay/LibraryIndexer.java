package in.akuj.jplay;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

/** One muted preview at a time, only while the library is foregrounded. */
final class LibraryIndexer implements SurfaceHolder.Callback {
    interface Listener { void changed(Uri uri); }
    private final JPlay app;private final LibraryStore store;private final SurfaceView surface;private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService disk=Executors.newSingleThreadExecutor();
    private final ArrayDeque<Uri> queue=new ArrayDeque<>();private final HashSet<String> scheduled=new HashSet<>();
    private Profile profile;private Media media;private MediaPlayer player;private Uri current;
    private boolean thumbnails=true;private boolean active,ready,capturing,released,disposing;private int generation;
    LibraryIndexer(JPlay app,SurfaceView surface,Listener listener){this.app=app;this.surface=surface;this.listener=listener;store=LibraryStore.get(app);surface.getHolder().addCallback(this);}
    void start(Profile profile,boolean thumbnails){if(released)return;this.profile=profile;this.thumbnails=thumbnails;active=true;ready=surface.getHolder().getSurface().isValid();next();}
    void request(Uri uri){if(!active||!profile.contains(uri)||scheduled.contains(uri.toString())||queue.size()>=80)return;LibraryStore.Item item=store.item(uri);if(item.indexed>0&&(!thumbnails||store.thumbnail(uri).isFile()))return;scheduled.add(uri.toString());queue.add(uri);next();}
    void refresh(Uri uri){store.thumbnail(uri).delete();scheduled.remove(uri.toString());if(active&&!queue.contains(uri)){scheduled.add(uri.toString());queue.addFirst(uri);next();}}
    boolean busy(){return current!=null;}
    void stop(){active=false;generation++;queue.clear();scheduled.clear();current=null;main.removeCallbacksAndMessages(null);dispose();}
    void release(){stop();released=true;surface.getHolder().removeCallback(this);disk.shutdown();}
    private boolean playbackActive(){PlaybackService service=PlaybackService.peek();return service!=null&&service.hasMedia();}
    private void next(){
        if(!active||!ready||disposing||current!=null||queue.isEmpty()||playbackActive()||!app.bindLocalNetwork())return;
        current=queue.removeFirst();final Uri uri=current;final int ticket=++generation;capturing=false;
        try{
            media=app.media(profile,uri);
            media.setEventListener(event->{if(ticket!=generation)return;if(event.type==Media.Event.ParsedChanged){store.updateMedia(uri,media);listener.changed(uri);openPreview(ticket);}});
            if(!media.parseAsync(Media.Parse.ParseNetwork,7000))openPreview(ticket);
            main.postDelayed(()->{if(ticket==generation&&player==null)openPreview(ticket);},8000);
            main.postDelayed(()->{if(ticket==generation)finish(ticket);},20000);
        }catch(RuntimeException e){finish(ticket);}
    }
    private void openPreview(int ticket){
        if(ticket!=generation||player!=null||media==null)return;
        if(playbackActive()){finish(ticket);return;}
        if(!thumbnails||store.thumbnail(current).isFile()){finish(ticket);return;}
        try{
            media.setEventListener(null);
            long duration=store.item(current).duration;
            double offset=duration>0?Math.min(60,Math.max(1,duration/10000.0)):12;
            media.addOption(":no-audio");media.addOption(":no-spu");media.addOption(":start-time="+offset);media.addOption(":network-caching=500");app.configureDecoder(media,current);
            player=new MediaPlayer(app.vlc());player.setVolume(0);
            player.getVLCVout().setVideoSurface(surface.getHolder().getSurface(),surface.getHolder());
            // Render into the whole surface so every output path fills the buffer PixelCopy reads.
            int sw=surface.getWidth(),sh=surface.getHeight();player.getVLCVout().setWindowSize(sw>0?sw:480,sh>0?sh:270);
            player.getVLCVout().attachViews((out,w,h,vw,vh,sn,sd)->{if(ticket==generation&&vw>0&&vh>0&&!capturing){capturing=true;main.postDelayed(()->capture(ticket),900);}});
            player.setEventListener(event->{if(ticket!=generation)return;if(event.type==MediaPlayer.Event.Playing){store.updateMedia(current,media);listener.changed(current);}
                // Hardware decoders can render without a layout callback; start from the video output instead.
                else if(event.type==MediaPlayer.Event.Vout&&event.getVoutCount()>0&&!capturing){capturing=true;main.postDelayed(()->capture(ticket),900);}else if(event.type==MediaPlayer.Event.EncounteredError||event.type==MediaPlayer.Event.EndReached)finish(ticket);});
            player.setMedia(media);player.play();
        }catch(RuntimeException e){finish(ticket);}
    }
    private void capture(int ticket){
        if(ticket!=generation||!active)return;
        if(!surface.getHolder().getSurface().isValid()){main.postDelayed(()->capture(ticket),500);return;}
        org.videolan.libvlc.interfaces.IMedia.Stats stats=media==null?null:media.getStats();
        if(stats==null||stats.displayedPictures<12){main.postDelayed(()->capture(ticket),500);return;}
        Bitmap bitmap=Bitmap.createBitmap(480,270,Bitmap.Config.ARGB_8888);final Uri uri=current;
        try{PixelCopy.request(surface,bitmap,result->{
            if(ticket!=generation){bitmap.recycle();return;}
            if(result==PixelCopy.SUCCESS){disk.execute(()->{Bitmap frame=trim(bitmap);store.saveThumbnail(uri,frame);if(frame!=bitmap)frame.recycle();bitmap.recycle();main.post(()->{if(!released)listener.changed(uri);});});finish(ticket);}
            else{bitmap.recycle();capturing=false;main.postDelayed(()->{if(ticket==generation){capturing=true;capture(ticket);}},600);}
        },main);}catch(RuntimeException e){bitmap.recycle();finish(ticket);}
    }
    /** Crops letterbox and pillarbox bars: rows and columns that are black edge to edge. */
    static Bitmap trim(Bitmap b){
        int w=b.getWidth(),h=b.getHeight(),top=0,bottom=h-1,left=0,right=w-1;
        int[] line=new int[Math.max(w,h)];
        while(top<bottom&&dark(b,line,0,top,w,1))top++;
        while(bottom>top&&dark(b,line,0,bottom,w,1))bottom--;
        while(left<right&&dark(b,line,left,top,1,bottom-top+1))left++;
        while(right>left&&dark(b,line,right,top,1,bottom-top+1))right--;
        int cw=right-left+1,ch=bottom-top+1;
        // A frame that is mostly black is a dark scene, not bars; keep it whole.
        if(cw<w*0.4f||ch<h*0.3f||(cw==w&&ch==h))return b;
        return Bitmap.createBitmap(b,left,top,cw,ch);
    }
    private static boolean dark(Bitmap b,int[] line,int x,int y,int w,int h){
        b.getPixels(line,0,w,x,y,w,h);
        for(int n=0;n<w*h;n++){int c=line[n];if(((c>>16)&0xff)>12||((c>>8)&0xff)>12||(c&0xff)>12)return false;}
        return true;
    }
    private void finish(int ticket){if(ticket!=generation)return;Uri done=current;generation++;main.removeCallbacksAndMessages(null);current=null;dispose();if(done!=null)listener.changed(done);main.postDelayed(this::next,180);}
    private void dispose(){
        MediaPlayer oldPlayer=player;Media oldMedia=media;player=null;media=null;
        if(oldPlayer==null&&oldMedia==null)return;
        if(oldPlayer!=null)oldPlayer.setEventListener(null);if(oldMedia!=null)oldMedia.setEventListener(null);disposing=true;
        // Stopping a NAS read can wait on native I/O. Never do it on the UI thread.
        disk.execute(()->{if(oldPlayer!=null)oldPlayer.stop();else if(oldMedia!=null)oldMedia.release();main.post(()->{
            if(oldPlayer!=null){oldPlayer.getVLCVout().detachViews();oldPlayer.release();if(oldMedia!=null)oldMedia.release();}
            disposing=false;next();
        });});
    }
    @Override public void surfaceCreated(SurfaceHolder holder){ready=true;next();}
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){ready=true;next();}
    @Override public void surfaceDestroyed(SurfaceHolder holder){ready=false;stop();}
}
