package in.akuj.jplay;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;
import org.json.*;

/** TVmaze catalogue with a portable, credential-free NAS sidecar as its durable cache. */
final class ShowMetadata {
    static final String LICENSE="https://creativecommons.org/licenses/by-sa/4.0/";
    private static final int JSON_LIMIT=8*1024*1024,IMAGE_LIMIT=4*1024*1024;
    private static final Pattern EPISODE=Pattern.compile("(?i)(?:^|[ ._-])(?:s(\\d{1,2})[ ._-]*e(\\d{1,3})|(\\d{1,2})x(\\d{1,3}))(?=$|[ ._-])");
    private static final Pattern SEASON=Pattern.compile("(?i)^(?:season[ ._-]*|s)(\\d{1,2})$");
    private static final Pattern SEASON_IN_NAME=Pattern.compile("(?i)(?:^|[ ._-])(?:season[ ._-]*|s)(\\d{1,2})(?=$|[ ._-])");
    private static ShowMetadata instance;
    private final JPlay app;private final LibraryStore store;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicInteger latest=new AtomicInteger();private long lastApi;
    interface Listener {void changed(Result result);}
    static final class Result {
        final Uri root;final JSONObject bundle;final JSONArray candidates;final String status;
        Result(Uri root,JSONObject bundle,JSONArray candidates,String status){this.root=root;this.bundle=bundle;this.candidates=candidates;this.status=status;}
    }
    static synchronized ShowMetadata get(JPlay app){if(instance==null)instance=new ShowMetadata(app);return instance;}
    private ShowMetadata(JPlay app){this.app=app;store=LibraryStore.get(app);}
    static Uri parent(Profile p,Uri uri){Uri.Builder b=p.root().buildUpon();List<String> parts=uri.getPathSegments();for(int n=1;n<parts.size()-1;n++)b.appendPath(parts.get(n));return b.build();}
    /** A "Season N" or "Specials" folder, whose show is its parent folder. */
    static boolean season(Uri folder){String name=folder.getLastPathSegment();return name!=null&&(SEASON.matcher(name).matches()||"Specials".equalsIgnoreCase(name));}
    static Uri root(Profile p,Uri folder,List<Uri> folders,List<Uri> videos){
        if(folder.getPathSegments().size()<2)return null;
        if(season(folder))return parent(p,folder);
        for(Uri child:folders)if(SEASON.matcher(child.getLastPathSegment()).matches())return folder;
        for(Uri child:videos)if(episodeNumber(child)!=null)return folder;
        String parent=parent(p,folder).getLastPathSegment();
        return parent!=null&&parent.matches("(?i)shows|tv|tv shows|series|television")?folder:null;
    }
    /** The season a folder name carries, however the release named it, or null when it carries none. */
    static String seasonName(String name){
        int number=seasonNumber(name);
        return number<0?null:number==0?"Specials":"Season "+number;
    }
    /** Season 0 is specials, -1 is no season at all. "S01", "Season 1" and "season.01" all read as 1. */
    static int seasonNumber(String name){
        if(name==null||name.isEmpty())return -1;
        if(name.toLowerCase(Locale.ROOT).contains("special"))return 0;
        Matcher m=SEASON_IN_NAME.matcher(name);
        return m.find()?Integer.parseInt(m.group(1)):-1;
    }
    /** Numbered seasons run in order and specials trail them, whatever the sort mode is. */
    static int seasonRank(Uri folder){
        int number=seasonNumber(folder.getLastPathSegment());
        return number<0?-1:number==0?Integer.MAX_VALUE:number;
    }
    static int[] episodeNumber(Uri uri){
        String name=uri.getLastPathSegment();Matcher m=EPISODE.matcher(name==null?"":name);
        if(!m.find())return null;return new int[]{Integer.parseInt(m.group(1)!=null?m.group(1):m.group(3)),Integer.parseInt(m.group(2)!=null?m.group(2):m.group(4))};
    }
    static String plain(String html){return html==null||html.equals("null")?"":android.text.Html.fromHtml(html,android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim();}
    static String value(JSONObject object,String key){return object==null||object.isNull(key)?"":object.optString(key);}
    static String genres(JSONObject show){JSONArray a=show.optJSONArray("genres");ArrayList<String> names=new ArrayList<>();if(a!=null)for(int n=0;n<a.length();n++)names.add(a.optString(n));return android.text.TextUtils.join(" · ",names);}
    static String label(JSONObject show){String date=value(show,"premiered");JSONObject channel=show.optJSONObject("network");if(channel==null)channel=show.optJSONObject("webChannel");return value(show,"name")+(date.length()>=4?" ("+date.substring(0,4)+")":"")+(channel==null?"":" · "+value(channel,"name"));}
    static String query(Uri root){return root.getLastPathSegment().replaceAll("(?i)[ ._-](?:1080p|2160p|720p|complete|bluray|web[ ._-]?dl).*","").replace('.',' ').replace('_',' ').replaceAll("\\s+"," ").trim();}
    private static String normalized(String name){return Normalizer.normalize(name,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
    private static JSONObject exact(JSONArray hits,String query){
        Matcher year=Pattern.compile("[ (\\[](19\\d{2}|20\\d{2})[)\\]]?$").matcher(query);String expectedYear="";
        if(year.find()){expectedYear=year.group(1);query=query.substring(0,year.start()).trim();}
        JSONObject match=null;String wanted=normalized(query);
        for(int i=0;i<hits.length();i++){JSONObject show=hits.optJSONObject(i).optJSONObject("show");if(show==null)continue;
            if(normalized(value(show,"name")).equals(wanted)&&(expectedYear.isEmpty()||value(show,"premiered").startsWith(expectedYear))){if(match!=null)return null;match=show;}}
        return match;
    }
    void load(Profile profile,Uri root,Uri directory,List<Uri> folders,List<Uri> videos,int chosenId,String searchQuery,boolean searchOnly,Listener listener){
        int ticket=latest.incrementAndGet();ArrayList<Uri> targets=new ArrayList<>(videos),children=new ArrayList<>(folders);
        worker.execute(()->{if(ticket!=latest.get())return;run(profile,root,directory,children,targets,chosenId,searchQuery,searchOnly,ticket,listener);});
    }
    private void emit(Uri root,JSONObject bundle,JSONArray candidates,String status,Listener listener){Result r=new Result(root,bundle,candidates,status);main.post(()->listener.changed(r));}
    private File cache(Uri root){return new File(app.getFilesDir(),"shows/"+LibraryStore.key(root)+".json");}
    private JSONObject local(Uri root){try(InputStream in=new FileInputStream(cache(root))){return parse(readBounded(in,JSON_LIMIT));}catch(Exception e){return null;}}
    private static JSONObject parse(byte[] bytes) throws Exception {
        JSONObject b=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        if(b.optInt("schema")!=1||!"TVmaze".equals(b.optString("provider"))||b.getJSONObject("show").optInt("id")<=0||b.optJSONArray("episodes")==null)throw new IOException("Unsupported metadata cache");
        return b;
    }
    private void saveLocal(Uri root,JSONObject bundle) throws Exception {
        File target=cache(root);if(!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IOException("Local cache unavailable");
        AtomicFile file=new AtomicFile(target);FileOutputStream out=null;
        try{out=file.startWrite();out.write(bundle.toString(2).getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
    }
    private void run(Profile profile,Uri root,Uri directory,List<Uri> folders,List<Uri> videos,int chosenId,String searchQuery,boolean searchOnly,int ticket,Listener listener){
        JSONObject bundle=local(root);boolean saved=false,fetched=false;
        try {
            if(!app.bindLocalNetwork())throw new IOException("Network unavailable");
            try(NasSidecar nas=new NasSidecar(profile,root)){
                if(searchOnly){JSONArray hits=new JSONArray(new String(download("https://api.tvmaze.com/search/shows?q="+Uri.encode(searchQuery),JSON_LIMIT),StandardCharsets.UTF_8));emit(root,bundle,hits,hits.length()==0?"No shows found. Try another title.":"Choose the show in this folder",listener);return;}
                byte[] onNas=nas.read("show.json",JSON_LIMIT);
                if(onNas!=null){JSONObject remote=parse(onNas);if(bundle==null||remote.optLong("fetchedAt")>=bundle.optLong("fetchedAt"))bundle=remote;}
                if(chosenId>0||bundle==null){
                    fetched=true;JSONObject show;
                    if(chosenId>0)show=new JSONObject(new String(download("https://api.tvmaze.com/shows/"+chosenId,JSON_LIMIT),StandardCharsets.UTF_8));
                    else{String q=query(root);JSONArray hits=new JSONArray(new String(download("https://api.tvmaze.com/search/shows?q="+Uri.encode(q),JSON_LIMIT),StandardCharsets.UTF_8));show=exact(hits,q);
                        if(show==null){emit(root,null,hits,hits.length()==0?"No exact match · search for this show":"Choose a match for this show",listener);return;}}
                    int id=show.getInt("id");JSONArray episodes=new JSONArray(new String(download("https://api.tvmaze.com/shows/"+id+"/episodes?specials=1",JSON_LIMIT),StandardCharsets.UTF_8));
                    bundle=new JSONObject().put("schema",1).put("provider","TVmaze").put("source",show.getString("url")).put("license",LICENSE).put("fetchedAt",System.currentTimeMillis()).put("match",chosenId>0?"user-selected":"unique-exact-title").put("show",show).put("episodes",episodes);
                }
                saveLocal(root,bundle);apply(bundle,root,directory,folders,videos);
                byte[] encoded=bundle.toString(2).getBytes(StandardCharsets.UTF_8);
                if(!Arrays.equals(encoded,onNas))nas.write("show.json",encoded,JSON_LIMIT);saved=true;
                emit(root,bundle,null,"Details saved beside this show · loading artwork…",listener);
                boolean complete=artwork(nas,bundle,root,directory,folders,videos,ticket);
                if(ticket==latest.get())emit(root,bundle,null,complete?"Saved beside this show · TVmaze":"Details saved · refresh to retry artwork",listener);
                android.util.Log.i("JPlay","showMetadata provider=TVmaze id="+bundle.getJSONObject("show").getInt("id")+" source="+(fetched?"api":"cache")+" nasSaved=true artwork="+complete+" episodes="+bundle.getJSONArray("episodes").length());
            }
        }catch(Exception e){
            if(bundle!=null){try{saveLocal(root,bundle);apply(bundle,root,directory,folders,videos);}catch(Exception ignored){}}
            emit(root,bundle,null,saved?"Details saved · artwork unavailable":bundle!=null?"Cached on phone · NAS save pending; refresh to retry":"Metadata unavailable · refresh or search again",listener);
            android.util.Log.w("JPlay","showMetadata failed stage="+(saved?"artwork":"metadata")+" type="+e.getClass().getSimpleName());
        }
    }
    private JSONObject episode(JSONObject bundle,Uri video){int[] n=episodeNumber(video);if(n==null)return null;JSONArray episodes=bundle.optJSONArray("episodes");for(int i=0;i<episodes.length();i++){JSONObject ep=episodes.optJSONObject(i);if(ep!=null&&!ep.isNull("number")&&ep.optInt("season")==n[0]&&ep.optInt("number")==n[1])return ep;}return null;}
    private void apply(JSONObject bundle,Uri root,Uri directory,List<Uri> folders,List<Uri> videos) throws Exception {
        JSONObject show=bundle.getJSONObject("show");store.updateShow(root,show,null,true);
        if(!directory.equals(root))store.updateShow(directory,show,null,false);
        for(Uri folder:folders)if(SEASON.matcher(folder.getLastPathSegment()).matches()||"Specials".equalsIgnoreCase(folder.getLastPathSegment()))store.updateShow(folder,show,null,false);
        for(Uri video:videos)store.updateShow(video,show,episode(bundle,video),false);
    }
    private static String imageUrl(JSONObject object){JSONObject image=object==null?null:object.optJSONObject("image");return value(image,"medium");}
    private boolean artwork(NasSidecar nas,JSONObject bundle,Uri root,Uri directory,List<Uri> folders,List<Uri> videos,int ticket) throws Exception {
        boolean okay=true;JSONObject show=bundle.getJSONObject("show");String poster=imageUrl(show);
        ArrayList<Uri> posterTargets=new ArrayList<>();posterTargets.add(root);posterTargets.add(directory);
        for(Uri folder:folders)if(SEASON.matcher(folder.getLastPathSegment()).matches())posterTargets.add(folder);
        if(!poster.isEmpty())try{byte[] bytes=art(nas,poster,"poster");for(Uri uri:posterTargets)saveArtwork(uri,bytes);}catch(Exception e){okay=false;}
        int count=0;
        for(Uri video:videos){if(ticket!=latest.get()||count++>=120){okay=false;break;}String url=imageUrl(episode(bundle,video));if(url.isEmpty())continue;
            try{saveArtwork(video,art(nas,url,"episode"));}catch(Exception e){okay=false;}}
        return okay;
    }
    private byte[] art(NasSidecar nas,String url,String type) throws Exception {
        String name=type+"-"+LibraryStore.key(Uri.parse(url))+".jpg";byte[] bytes=nas.read(name,IMAGE_LIMIT);
        if(bytes==null){bytes=download(url,IMAGE_LIMIT);validateImage(bytes);nas.write(name,bytes,IMAGE_LIMIT);}return bytes;
    }
    private static void validateImage(byte[] bytes) throws IOException {BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);if(o.outWidth<1||o.outHeight<1||o.outWidth>8192||o.outHeight>8192)throw new IOException("Invalid artwork");}
    private void saveArtwork(Uri uri,byte[] bytes) throws Exception {
        validateImage(bytes);BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>960)o.inSampleSize*=2;o.inJustDecodeBounds=false;
        Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);if(bitmap==null)throw new IOException("Artwork decode failed");try{store.saveOnlineThumbnail(uri,bitmap);}finally{bitmap.recycle();}
    }
    private byte[] download(String address,int limit) throws Exception {
        URL url=new URL(address);String host=url.getHost();if(!"https".equals(url.getProtocol())||!(host.equals("api.tvmaze.com")||host.equals("static.tvmaze.com")))throw new IOException("Unsupported artwork source");
        for(int attempt=0;attempt<3;attempt++){
            if(host.equals("api.tvmaze.com")){long wait=600-(android.os.SystemClock.elapsedRealtime()-lastApi);if(wait>0)Thread.sleep(wait);lastApi=android.os.SystemClock.elapsedRealtime();}
            HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","JustPlay/1.3 (Android; TVmaze library cache)");
            try{int code=c.getResponseCode();if(code==429&&attempt<2){Thread.sleep(2500L*(attempt+1));continue;}if(code!=200)throw new IOException("Metadata HTTP "+code);try(InputStream in=c.getInputStream()){return readBounded(in,limit);}}finally{c.disconnect();}
        }
        throw new IOException("Metadata rate limited");
    }
    static byte[] readBounded(InputStream in,int limit) throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>limit)throw new IOException("Metadata response too large");out.write(buffer,0,n);}return out.toByteArray();}
}
