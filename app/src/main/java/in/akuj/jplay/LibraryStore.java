package in.akuj.jplay;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.videolan.libvlc.interfaces.IMedia;

/** Local, credential-free catalogue. Metadata and watch history survive cache eviction. */
final class LibraryStore extends SQLiteOpenHelper {
    private static LibraryStore instance;
    private final Context context;
    private final HashMap<String,Long> sessions = new HashMap<>();
    private final java.util.concurrent.ExecutorService images=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Object thumbnailLock=new Object();
    private static final Pattern YEAR = Pattern.compile("(?:^|[ ._(\\[])(19\\d{2}|20\\d{2})(?:[ ._)\\]]|$)");

    static synchronized LibraryStore get(Context context) {
        if (instance == null) instance = new LibraryStore(context.getApplicationContext());
        return instance;
    }
    private LibraryStore(Context c) { super(c,"library.db",null,1); context=c; setWriteAheadLoggingEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE videos(uri TEXT PRIMARY KEY,metadata TEXT NOT NULL DEFAULT '{}',position INTEGER NOT NULL DEFAULT 0,duration INTEGER NOT NULL DEFAULT 0,last_played INTEGER NOT NULL DEFAULT 0,completed INTEGER NOT NULL DEFAULT 0,indexed INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE plays(id INTEGER PRIMARY KEY AUTOINCREMENT,uri TEXT NOT NULL,started INTEGER NOT NULL,position INTEGER NOT NULL DEFAULT 0,duration INTEGER NOT NULL DEFAULT 0,completed INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX history_time ON plays(started DESC)");
        db.execSQL("CREATE INDEX recent_time ON videos(last_played DESC)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { }
    private void ensure(Uri uri) {
        if (!"smb".equals(uri.getScheme()) || uri.getEncodedAuthority()==null || uri.getEncodedAuthority().contains("@")) throw new IllegalArgumentException("Expected credential-free NAS URI");
        ContentValues v=new ContentValues(); v.put("uri",uri.toString());
        v.put("position",context.getSharedPreferences("positions",Context.MODE_PRIVATE).getLong(uri.toString(),0));
        getWritableDatabase().insertWithOnConflict("videos",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }
    synchronized Item item(Uri uri) {
        try (Cursor c=getReadableDatabase().query("videos",null,"uri=?",new String[]{uri.toString()},null,null,null)) {
            if(c.moveToFirst()) return read(c);
        }
        Item i=new Item(uri);
        i.position=context.getSharedPreferences("positions",Context.MODE_PRIVATE).getLong(uri.toString(),0);
        return i;
    }
    synchronized long position(Uri uri) { Item i=item(uri); return i.completed?0:i.position; }
    synchronized long beginSession(Uri uri) {
        ensure(uri);ContentValues p=new ContentValues();p.put("uri",uri.toString());p.put("started",System.currentTimeMillis());
        return getWritableDatabase().insertOrThrow("plays",null,p);
    }
    synchronized void begin(Uri uri) {sessions.put(uri.toString(),beginSession(uri));}
    synchronized void record(Uri uri,long positionMs,long durationMs,boolean completed) {
        if(!sessions.containsKey(uri.toString()))begin(uri);
        recordSession(uri,sessions.get(uri.toString()),positionMs,durationMs,completed,true);
    }
    // Each playback session has its own history ID; the catalogue keeps the resume position.
    synchronized void recordSession(Uri uri,long sessionId,long positionMs,long durationMs,boolean completed,boolean catalogue) {
        ensure(uri);long position=completed?0:Math.max(0,positionMs),duration=Math.max(0,durationMs);
        if(catalogue){
            ContentValues v=new ContentValues();v.put("position",position);v.put("completed",completed?1:0);v.put("last_played",System.currentTimeMillis());
            if(duration>0)v.put("duration",duration);
            getWritableDatabase().update("videos",v,"uri=?",new String[]{uri.toString()});
            context.getSharedPreferences("positions",Context.MODE_PRIVATE).edit().putLong(uri.toString(),position).apply();
        }
        ContentValues p=new ContentValues();p.put("position",position);p.put("duration",duration);p.put("completed",completed?1:0);
        getWritableDatabase().update("plays",p,"id=? AND uri=?",new String[]{Long.toString(sessionId),uri.toString()});
    }
    synchronized void markWatched(Uri uri,boolean watched) {
        ensure(uri);ContentValues v=new ContentValues();v.put("completed",watched?1:0);v.put("position",0);
        getWritableDatabase().update("videos",v,"uri=?",new String[]{uri.toString()});
        context.getSharedPreferences("positions",Context.MODE_PRIVATE).edit().remove(uri.toString()).apply();
    }
    synchronized void clearHistory() {
        getWritableDatabase().beginTransaction();
        try {getWritableDatabase().delete("plays",null,null);getWritableDatabase().execSQL("UPDATE videos SET position=0,last_played=0,completed=0");getWritableDatabase().setTransactionSuccessful();}
        finally {getWritableDatabase().endTransaction();}
        sessions.clear();context.getSharedPreferences("positions",Context.MODE_PRIVATE).edit().clear().apply();
    }
    /** The newest files the library knows about, whatever folder they landed in. */
    synchronized ArrayList<Item> added(Profile profile,int limit) {
        ArrayList<Item> all=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("videos",null,null,null,null,null,null)) {
            while(c.moveToNext()){
                Item i=read(c);
                if(i.metadata.optLong("added")<=0||i.metadata.optBoolean("folder"))continue;
                if(profile.contains(i.uri))all.add(i);
            }
        }
        java.util.Collections.sort(all,(a,b)->Long.compare(b.metadata.optLong("added"),a.metadata.optLong("added")));
        return all.size()>limit?new ArrayList<>(all.subList(0,limit)):all;
    }
    /** The folder stamp we last read this folder's children at: an unchanged folder is skipped. */
    synchronized long scanned(Uri uri){return item(uri).metadata.optLong("scanned");}
    synchronized void markScanned(Uri uri,long stamp) {
        if(stamp<=0)return;
        ensure(uri);
        try {
            JSONObject data=item(uri).metadata;
            if(data.optLong("scanned")==stamp)return;
            data.put("scanned",stamp).put("folder",true);
            ContentValues v=new ContentValues();v.put("metadata",data.toString());
            getWritableDatabase().update("videos",v,"uri=?",new String[]{uri.toString()});
        } catch(Exception ignored){}
    }
    /** When this file or folder last changed on the NAS, for ordering a library by what is new. */
    synchronized void markAdded(Uri uri,long added,boolean folder) {
        if(added<=0)return;
        ensure(uri);
        try {
            JSONObject data=item(uri).metadata;
            if(data.optLong("added")==added&&data.optBoolean("folder")==folder)return;
            data.put("added",added).put("folder",folder);
            ContentValues v=new ContentValues();v.put("metadata",data.toString());
            getWritableDatabase().update("videos",v,"uri=?",new String[]{uri.toString()});
        } catch(Exception ignored){}
    }
    /** Every known video sitting directly in a folder, ordered the way episodes run. */
    synchronized ArrayList<Item> folder(Uri directory) {
        ArrayList<Item> result=new ArrayList<>();
        String prefix=directory.toString()+"/";
        String pattern=prefix.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        try(Cursor c=getReadableDatabase().query("videos",null,"uri LIKE ? ESCAPE '\\'",new String[]{pattern},null,null,"uri ASC")) {
            while(c.moveToNext()){Item i=read(c);if(!i.uri.toString().substring(prefix.length()).contains("/"))result.add(i);}
        }
        java.util.Collections.sort(result,(a,b)->{
            int[] x=ShowMetadata.episodeNumber(a.uri),y=ShowMetadata.episodeNumber(b.uri);
            if(x!=null&&y!=null){int season=Integer.compare(x[0],y[0]);if(season!=0)return season;int episode=Integer.compare(x[1],y[1]);if(episode!=0)return episode;}
            else if(x!=null)return -1;
            else if(y!=null)return 1;
            return a.filename.compareToIgnoreCase(b.filename);
        });
        return result;
    }
    synchronized ArrayList<Item> recent(Profile profile,boolean unfinished,int limit) {
        ArrayList<Item> result=new ArrayList<>();
        String where=unfinished?"last_played>0 AND position>0 AND completed=0":"last_played>0";
        try(Cursor c=getReadableDatabase().query("videos",null,where,null,null,null,"last_played DESC")) {
            while(c.moveToNext()&&result.size()<limit){Item i=read(c);if(profile.contains(i.uri))result.add(i);}
        }
        return result;
    }
    synchronized ArrayList<Item> history(Profile profile,int limit) {
        ArrayList<Item> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT v.*,p.started AS watched_at,p.position AS watched_position,p.completed AS watched_completed FROM plays p JOIN videos v ON p.uri=v.uri ORDER BY p.started DESC",null)) {
            while(c.moveToNext()&&result.size()<limit){Item i=read(c);i.lastPlayed=c.getLong(c.getColumnIndexOrThrow("watched_at"));i.position=c.getLong(c.getColumnIndexOrThrow("watched_position"));i.completed=c.getInt(c.getColumnIndexOrThrow("watched_completed"))!=0;if(profile.contains(i.uri))result.add(i);}
        }
        return result;
    }
    synchronized void updateMedia(Uri uri,IMedia media) {
        ensure(uri);
        try {
            JSONObject data=item(uri).metadata;
            String embedded=media.getMeta(IMedia.Meta.Title);
            if(embedded!=null&&!embedded.trim().isEmpty())data.put("embeddedTitle",embedded);
            int[] keys={IMedia.Meta.Artist,IMedia.Meta.Genre,IMedia.Meta.Description,IMedia.Meta.Date,IMedia.Meta.Director,IMedia.Meta.ShowName,IMedia.Meta.Season,IMedia.Meta.Episode};
            String[] names={"artist","genre","description","date","director","show","season","episode"};
            for(int n=0;n<keys.length;n++){String value=media.getMeta(keys[n]);if(value!=null&&!value.trim().isEmpty())data.put(names[n],value);}
            JSONArray tracks=new JSONArray();
            for(int n=0;n<media.getTrackCount();n++) {
                IMedia.Track t=media.getTrack(n);if(t==null)continue;
                JSONObject track=new JSONObject().put("type",t.type).put("codec",t.codec).put("language",t.language==null?"":t.language).put("description",t.description==null?"":t.description).put("bitrate",t.bitrate);
                if(t instanceof IMedia.VideoTrack){IMedia.VideoTrack v=(IMedia.VideoTrack)t;track.put("width",v.width).put("height",v.height).put("fps",v.frameRateDen>0?(double)v.frameRateNum/v.frameRateDen:0);if(!data.has("width")||v.width>data.optInt("width")){data.put("width",v.width).put("height",v.height).put("videoCodec",t.codec).put("fps",track.optDouble("fps"));}}
                if(t instanceof IMedia.AudioTrack){IMedia.AudioTrack a=(IMedia.AudioTrack)t;track.put("channels",a.channels).put("sampleRate",a.rate);if(!data.has("audioCodec"))data.put("audioCodec",t.codec).put("channels",a.channels).put("sampleRate",a.rate);}
                tracks.put(track);
            }
            if(tracks.length()>0)data.put("tracks",tracks);
            ContentValues values=new ContentValues();values.put("metadata",data.toString());values.put("indexed",System.currentTimeMillis());
            if(media.getDuration()>0)values.put("duration",media.getDuration());
            getWritableDatabase().update("videos",values,"uri=?",new String[]{uri.toString()});
        }catch(org.json.JSONException ignored){ }
    }
    synchronized void updateShow(Uri uri,JSONObject show,JSONObject episode,boolean showFolder) throws org.json.JSONException {
        ensure(uri);JSONObject data=item(uri).metadata;
        if(data.optInt("tvmazeShow")!=show.optInt("id")||data.optInt("tvmazeEpisode")!=(episode==null?0:episode.optInt("id")))onlineThumbnail(uri).delete();
        data.put("tvmazeShow",show.getInt("id")).put("tvmazeShowName",ShowMetadata.value(show,"name"))
            .put("tvmazeUrl",ShowMetadata.value(episode==null?show:episode,"url"))
            .put("tvmazeGenre",ShowMetadata.genres(show)).put("tvmazeStatus",ShowMetadata.value(show,"status"));
        String title="";
        if(showFolder)title=ShowMetadata.value(show,"name");
        else if(episode!=null)title=String.format(Locale.ROOT,"S%02dE%02d · %s",episode.optInt("season"),episode.optInt("number"),ShowMetadata.value(episode,"name"));
        data.put("tvmazeTitle",title).put("tvmazeDescription",ShowMetadata.plain(ShowMetadata.value(episode==null?show:episode,"summary")))
            .put("tvmazeDate",ShowMetadata.value(episode==null?show:episode,episode==null?"premiered":"airdate"));
        JSONObject rating=(episode==null?show:episode).optJSONObject("rating");data.put("tvmazeRating",ShowMetadata.value(rating,"average"));
        data.put("tvmazeEpisode",episode==null?0:episode.optInt("id"));
        ContentValues values=new ContentValues();values.put("metadata",data.toString());getWritableDatabase().update("videos",values,"uri=?",new String[]{uri.toString()});
    }
    private File onlineThumbnail(Uri uri){return new File(context.getCacheDir(),"thumbs/"+key(uri)+".online.jpg");}
    File thumbnail(Uri uri) {File online=onlineThumbnail(uri);return online.isFile()?online:new File(context.getCacheDir(),"thumbs/"+key(uri)+".jpg");}
    void saveOnlineThumbnail(Uri uri,Bitmap bitmap){writeThumbnail(uri,bitmap,true);}
    void saveThumbnail(Uri uri,Bitmap bitmap) {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()){
            Bitmap copy=bitmap.copy(Bitmap.Config.ARGB_8888,false);
            if(copy!=null)images.execute(()->{writeThumbnail(uri,copy);copy.recycle();});
        }else writeThumbnail(uri,bitmap);
    }
    private void writeThumbnail(Uri uri,Bitmap bitmap){writeThumbnail(uri,bitmap,false);}
    private void writeThumbnail(Uri uri,Bitmap bitmap,boolean online) { synchronized(thumbnailLock) {
        File target=online?onlineThumbnail(uri):new File(context.getCacheDir(),"thumbs/"+key(uri)+".jpg");File directory=target.getParentFile();
        if(!directory.isDirectory()&&!directory.mkdirs())return;
        AtomicFile file=new AtomicFile(target);FileOutputStream out=null;
        try {out=file.startWrite();if(!bitmap.compress(Bitmap.CompressFormat.JPEG,85,out))throw new java.io.IOException("Thumbnail encode failed");file.finishWrite(out);}
        catch(Exception e){if(out!=null)file.failWrite(out);}
        // Thumbnails are a bounded, regenerable cache; history lives in SQLite.
        File[] files=directory.listFiles();if(files!=null&&files.length>300){java.util.Arrays.sort(files,(a,b)->Long.compare(a.lastModified(),b.lastModified()));for(int n=0;n<files.length-300;n++)files[n].delete();}
    } }
    void clearThumbnails() {File[] files=new File(context.getCacheDir(),"thumbs").listFiles();if(files!=null)for(File f:files)f.delete();}
    static String key(Uri uri) {
        try {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(uri.toString().getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private Item read(Cursor c) {
        Item i=new Item(Uri.parse(c.getString(c.getColumnIndexOrThrow("uri"))));
        try{i.metadata=new JSONObject(c.getString(c.getColumnIndexOrThrow("metadata")));}catch(Exception ignored){}
        i.position=c.getLong(c.getColumnIndexOrThrow("position"));i.duration=c.getLong(c.getColumnIndexOrThrow("duration"));i.lastPlayed=c.getLong(c.getColumnIndexOrThrow("last_played"));i.completed=c.getInt(c.getColumnIndexOrThrow("completed"))!=0;i.indexed=c.getLong(c.getColumnIndexOrThrow("indexed"));
        return i;
    }
    static final class Item {
        final Uri uri;final String filename;JSONObject metadata=new JSONObject();long position,duration,lastPlayed,indexed;boolean completed;
        Item(Uri u){uri=u;filename=u.getLastPathSegment()==null?"Video":u.getLastPathSegment();}
        String year(){String date=metadata.optString("tvmazeDate",metadata.optString("date"));if(date.matches("(?:19|20)\\d{2}.*"))return date.substring(0,4);Matcher m=YEAR.matcher(filename);return m.find()?m.group(1):"";}
        String title(){
            String online=metadata.optString("tvmazeTitle");if(!online.isEmpty())return online;
            String t=filename.replaceFirst("\\.[^.]+$","");
            Matcher episode=Pattern.compile("(?i)(?:^|[ ._-])(S[0-9]{1,2}E[0-9]{1,3}(?:E[0-9]{1,3})?)").matcher(t);
            String number="";if(episode.find()){number=episode.group(1).toUpperCase(Locale.ROOT);t=t.substring(0,episode.start());}
            Matcher m=YEAR.matcher(t);if(m.find()&&m.start()>0)t=t.substring(0,m.start());else t=t.replaceFirst("(?i)[ ._-](?:2160p|1080p|720p|480p|4K|BluRay|WEB[ ._-]?DL|WEBRip|HDTV|x26[45]|HEVC).*","");
            t=t.replace('.',' ').replace('_',' ').replaceAll("\\s+"," ").trim();
            String embedded=metadata.optString("embeddedTitle").trim();
            if(!embedded.isEmpty()&&!embedded.equals(filename)&&embedded.length()<160&&!embedded.matches("(?i).*(?:2160p|1080p|720p|WEB-DL|WEBRip|BluRay|x264|x265).*"))t=embedded;
            if(t.isEmpty())t=filename;
            if(!number.isEmpty()&&!t.toUpperCase(Locale.ROOT).contains(number))t+=" · "+number;
            return t;
        }
        String format(){int h=metadata.optInt("height"),w=metadata.optInt("width");if(w>=3800||h>=2100)return "4K";if(w>=1900||h>=1000)return "1080p";if(w>=1200||h>=700)return "720p";return h>0?h+"p":"";}
        String summary(){ArrayList<String> p=new ArrayList<>();if(!year().isEmpty())p.add(year());if(duration>0)p.add(duration>=3600000?(duration/3600000)+"h "+((duration/60000)%60)+"m":(duration/60000)+" min");if(!format().isEmpty())p.add(format());return android.text.TextUtils.join("  ·  ",p);}
        float progress(){return duration>0?Math.min(1f,(float)position/duration):0;}
    }
}
