package in.akuj.jplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaList;
import org.videolan.libvlc.interfaces.IMedia;

public final class MainActivity extends Activity {
    private final java.util.concurrent.ExecutorService nasWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean recent;private boolean crawling;private long lastCrawl;
    private ShowMetadata shows;private Uri showRoot;private ShowMetadata.Result showResult;private String showStatus="";
    private JPlay app;private Profile profile;private LibraryStore store;private LibraryIndexer indexer;
    private FrameLayout content;private LinearLayout nowPlaying;private TextView nowTitle,nowProgress;private ImageButton nowToggle;private Uri nowUri;private boolean scanningSuspended;private Uri directory;private Media listing;
    private LinearLayout header,tray,trayFolders;private boolean trayMode;private ListView list;private EditText search;private TextView status,location;private ProgressBar progress;private Dialog detailsDialog;private Uri detailUri;private TextView detailTitle,detailSummary,detailVideo,detailAudio,detailSubs,detailDescription;private ArtworkView detailArt;
    private final ArrayList<Entry> entries=new ArrayList<>(),visible=new ArrayList<>();private final ArrayList<ArrayList<Entry>> rows=new ArrayList<>();
    private final Handler handler=new Handler();private final Handler playbackUi=new Handler();
    private final Runnable playbackTick=new Runnable(){public void run(){if(!foreground)return;updateNowPlaying();playbackUi.postDelayed(this,700);}};private Cards adapter;private String focusedCard;private boolean rowRefreshPending;private String restoreFocusTag;private int generation,columns=2,sortMode;private boolean browsing,history,foreground;
    private final Runnable networkChanged=()->{if(browsing&&directory!=null&&!history)browse(directory);};
    @Override protected void attachBaseContext(android.content.Context base){super.attachBaseContext(Ui.scaled(base));}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);app=(JPlay)getApplication();RemoteService.start(this);store=LibraryStore.get(this);shows=ShowMetadata.get(app);
        getWindow().setStatusBarColor(Ui.BG);getWindow().setNavigationBarColor(Ui.BG);
        try{profile=Profile.load(this);}catch(Exception e){profile=new Profile();}
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Ui.BG);setContentView(root);
        // The opaque library covers this preview surface. PixelCopy reads the video buffer,
        // not screenshots of the user's desktop or other applications.
        SurfaceView preview=new SurfaceView(this);root.addView(preview,new FrameLayout.LayoutParams(Ui.dp(this,480),Ui.dp(this,270)));
        content=new FrameLayout(this);content.setBackgroundColor(Ui.BG);content.setPadding(Ui.overscanX(this),Ui.overscanY(this),Ui.overscanX(this),Ui.overscanY(this));root.addView(content,new FrameLayout.LayoutParams(-1,-1));
        indexer=new LibraryIndexer(app,preview,uri->{if(adapter!=null&&browsing){refreshRowsSoon();refreshArtwork(header,uri);refreshDetails();updateStatus();}});
        columns=Math.max(2,Math.min(4,getResources().getConfiguration().screenWidthDp/175));
        sortMode=getPreferences(MODE_PRIVATE).getInt("sort",4);
        if(profile.host.isEmpty())settings();else{history=state!=null&&state.getBoolean("history");recent=getIntent().getBooleanExtra("recent",false);library(rememberedDirectory(state));}
    }
    @Override protected void onStart(){super.onStart();foreground=true;app.listen(networkChanged);if(browsing){startIndexer();if(!history&&!entries.isEmpty())loadShowMetadata(0,null,false);}playbackUi.post(playbackTick);}
    @Override protected void onResume(){super.onResume();if(browsing){Uri remembered=rememberedDirectory(null);if(!remembered.equals(directory))library(remembered);if(history)populateHistory();else{refreshRows();rebuildHeader();}handler.postDelayed(()->{if(browsing&&adapter!=null){if(history)populateHistory();else refreshRows();rebuildHeader();}},400);}}
    @Override protected void onStop(){foreground=false;playbackUi.removeCallbacksAndMessages(null);app.unlisten(networkChanged);indexer.stop();cancelListing();super.onStop();}
    @Override protected void onDestroy(){indexer.release();cancelListing();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){if(directory!=null)state.putString("directory",directory.toString());state.putBoolean("history",history);super.onSaveInstanceState(state);}
    private String directoryPreference(){return "directory."+LibraryStore.key(profile.root());}
    private Uri rememberedDirectory(Bundle state){
        String fallback=state==null?profile.start().toString():state.getString("directory",profile.start().toString());
        Uri saved=Uri.parse(getPreferences(MODE_PRIVATE).getString(directoryPreference(),fallback));
        return profile.contains(saved)?saved:profile.start();
    }
    private boolean playbackActive(){PlaybackService service=PlaybackService.peek();return service!=null&&service.hasMedia();}
    private void startIndexer(){if(playbackActive()){scanningSuspended=true;indexer.stop();}else{scanningSuspended=false;indexer.start(profile,getPreferences(MODE_PRIVATE).getBoolean("thumbnails",true));}}
    /** The remote's play key returns to whatever is already playing, rather than doing nothing. */
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        int code=event.getKeyCode();
        if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0
            &&(code==KeyEvent.KEYCODE_MEDIA_PLAY||code==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)&&playbackActive()){
            openNowPlaying(); return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private void openNowPlaying(){Intent intent=PlaybackService.resumeIntent(this);if(intent!=null){indexer.stop();startActivity(intent);}}
    private void toggleNowPlaying(){PlaybackService.toggle(this);}
    private void stopNowPlaying(){PlaybackService.stop(this);}
    private void addNowPlaying(LinearLayout shell){
        nowUri=null;nowPlaying=Ui.row(this);nowPlaying.setPadding(dp(16),dp(10),dp(12),dp(10));nowPlaying.setBackground(Ui.background(Ui.CARD,16,this));nowPlaying.setVisibility(View.GONE);
        LinearLayout words=Ui.column(this);words.setOnClickListener(v->openNowPlaying());Ui.focusRing(words,10);TextView label=Ui.label(this,"NOW PLAYING");label.setTextColor(Ui.ACCENT);words.addView(label);nowTitle=Ui.heading(this,"",15);nowTitle.setSingleLine();nowTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(nowTitle);nowProgress=Ui.text(this,"",11,Ui.MUTED);nowProgress.setSingleLine();nowProgress.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(nowProgress);nowPlaying.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        nowToggle=Ui.iconButton(this,"pause","Pause playback",this::toggleNowPlaying);nowPlaying.addView(nowToggle,margins(dp(46),dp(46),8,0,4,0));nowPlaying.addView(Ui.iconButton(this,"close","Stop playback",this::stopNowPlaying),new LinearLayout.LayoutParams(dp(46),dp(46)));shell.addView(nowPlaying,margins(-1,-2,20,0,20,12));updateNowPlaying();
    }
    private void updateNowPlaying(){
        PlaybackService service=PlaybackService.peek();boolean active=service!=null&&service.hasMedia();
        if(browsing&&active&&!scanningSuspended){scanningSuspended=true;indexer.stop();updateStatus();}
        if(browsing&&!active&&scanningSuspended){startIndexer();refreshRows();}
        if(nowPlaying==null)return;nowPlaying.setVisibility(browsing&&active?View.VISIBLE:View.GONE);if(!browsing||!active){nowUri=null;return;}
        Uri uri=service.currentUri();if(uri==null)return;nowTitle.setText(service.mediaTitle());nowProgress.setText((service.isPlaying()?"Playing · ":service.status()+" · ")+Ui.time(service.positionMs())+" · "+returnHint());
        nowToggle.setImageDrawable(new Ui.Icon(service.wantsPlay()&&!service.failed()&&!service.ended()?"pause":"play",Ui.INK));nowToggle.setContentDescription(service.wantsPlay()&&!service.failed()&&!service.ended()?"Pause playback":"Resume playback");nowUri=uri;
    }
    private static final int TRAY=200,TRAY_BG=0xff1a1324;
    private String returnHint(){return Ui.tv(this)?"Press play to return":"Tap to return";}
    private int dp(int value){return Ui.dp(this,value);}
    private LinearLayout.LayoutParams margins(int width,int height,int left,int top,int right,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(width,height);p.setMargins(dp(left),dp(top),dp(right),dp(bottom));return p;}
    private TextView display(String text,int size){TextView t=Ui.text(this,text,size,Ui.INK);t.setTypeface(Typeface.create("serif",Typeface.NORMAL));return t;}
    private void mount(View view){nowPlaying=null;nowUri=null;content.removeAllViews();content.addView(view,new FrameLayout.LayoutParams(-1,-1));}
    private void library(Uri initial){
        browsing=true;directory=profile.contains(initial)?initial:profile.root();
        LinearLayout shell=Ui.column(this);shell.setBackgroundColor(Ui.BG);
        trayMode=Ui.tv(this);tray=null;trayFolders=null;
        if(trayMode){
            columns=4;
            tray=Ui.column(this);tray.setBackgroundColor(TRAY_BG);
            LinearLayout split=Ui.row(this);split.setGravity(Gravity.TOP);split.setBackgroundColor(Ui.BG);
            split.addView(tray,new LinearLayout.LayoutParams(dp(TRAY),-1));
            View edge=new View(this);edge.setBackgroundColor(Ui.LINE);split.addView(edge,new LinearLayout.LayoutParams(1,-1));
            split.addView(shell,new LinearLayout.LayoutParams(0,-1,1));
            mount(split);
        }else mount(shell);
        LinearLayout brand=Ui.row(this);brand.setPadding(dp(22),dp(13),dp(18),dp(13));
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.just_play_mark);logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);brand.addView(logo,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout wordmark=Ui.column(this);TextView mark=Ui.heading(this,getString(R.string.app_name),21);mark.setSingleLine();mark.setLetterSpacing(-.025f);wordmark.addView(mark);TextView by=Ui.text(this,"BY NAVI",8,0xffad91ff);by.setLetterSpacing(.24f);wordmark.addView(by);brand.addView(wordmark,margins(Ui.tv(this)?-2:0,-2,7,0,4,0));if(!Ui.tv(this))((LinearLayout.LayoutParams)wordmark.getLayoutParams()).weight=1;
        if(!trayMode){brand.addView(Ui.iconButton(this,"settings","Library and NAS settings",this::settings),new LinearLayout.LayoutParams(dp(44),dp(44)));}shell.addView(brand);
        LinearLayout tabs=Ui.row(this);tabs.setPadding(dp(20),0,dp(20),dp(14));
        Button library=Ui.button(this,"Library",()->{history=false;recent=false;library(directory);});Button historyTab=Ui.button(this,"History",()->{history=true;recent=false;library(directory);});
        (history?historyTab:library).setTextColor(Ui.BG);(history?historyTab:library).setBackground(Ui.background(Ui.ACCENT,12,this));
        tabs.addView(library,new LinearLayout.LayoutParams(0,dp(43),1));tabs.addView(historyTab,margins(0,dp(43),8,0,0,0));((LinearLayout.LayoutParams)historyTab.getLayoutParams()).weight=1;shell.addView(tabs);
        LinearLayout filterRow=Ui.row(this);filterRow.setPadding(dp(20),0,dp(20),dp(12));
        LinearLayout searchBox=Ui.row(this);searchBox.setBackground(Ui.background(Ui.CARD,12,this));
        ImageView searchIcon=new ImageView(this);searchIcon.setImageDrawable(new Ui.Icon("search",Ui.MUTED));searchBox.addView(searchIcon,margins(dp(19),dp(19),14,0,2,0));
        search=new EditText(this);search.setSingleLine();search.setTextSize(14);search.setTextColor(Ui.INK);search.setHintTextColor(Ui.MUTED);search.setHint(Ui.tv(this)?(history?"Search history":"Search"):(history?"Search your history":"Search this folder"));search.setBackgroundColor(Color.TRANSPARENT);search.setPadding(dp(8),0,dp(12),0);search.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);Ui.focusRing(search,12);searchBox.addView(search,new LinearLayout.LayoutParams(0,dp(46),1));
        filterRow.addView(searchBox,new LinearLayout.LayoutParams(0,dp(46),1));filterRow.addView(Ui.iconButton(this,history?"more":"settings",history?"History actions":"Sort library",history?this::historyActions:this::sort),margins(dp(46),dp(46),8,0,0,0));shell.addView(filterRow);
        if(trayMode){
            shell.removeView(brand);shell.removeView(tabs);
            brand.setPadding(dp(16),dp(15),dp(12),dp(17));tray.addView(brand);
            tray.addView(trayItem("folder","Library",!history&&!recent,()->{history=false;recent=false;library(directory);}));
            tray.addView(trayItem("new","Recently Added",recent,()->{recent=true;history=false;library(directory);}));
            tray.addView(trayItem("history","History",history,()->{history=true;recent=false;library(directory);}));
            trayFolders=Ui.column(this);ScrollView folders=new ScrollView(this);folders.setVerticalScrollBarEnabled(false);folders.setClipToPadding(false);folders.addView(trayFolders);
            tray.addView(folders,new LinearLayout.LayoutParams(-1,0,1));
            View line=new View(this);line.setBackgroundColor(Ui.LINE);tray.addView(line,margins(-1,1,14,6,14,6));
            tray.addView(trayItem("settings","Settings",false,this::settings));
            filterRow.setPadding(dp(18),dp(13),dp(6),dp(13));
        }
        else if(getResources().getConfiguration().screenWidthDp>=600){
            shell.removeView(brand);shell.removeView(tabs);shell.removeView(filterRow);LinearLayout toolbar=Ui.row(this);toolbar.setPadding(dp(12),dp(6),dp(12),dp(6));
            brand.setPadding(dp(8),0,dp(8),0);tabs.setPadding(0,0,dp(10),0);filterRow.setPadding(0,0,0,0);
            boolean tv=Ui.tv(this);toolbar.addView(brand,new LinearLayout.LayoutParams(tv?-2:dp(270),dp(50)));toolbar.addView(tabs,new LinearLayout.LayoutParams(tv?-2:dp(220),dp(50)));toolbar.addView(filterRow,new LinearLayout.LayoutParams(0,dp(50),1));shell.addView(toolbar);
        }
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));progress.setVisibility(View.GONE);shell.addView(progress,new LinearLayout.LayoutParams(-1,dp(2)));
        list=new ListView(this);list.setItemsCanFocus(true);list.setDivider(null);list.setClipToPadding(false);list.setPadding(dp(trayMode?8:20),0,dp(trayMode?8:20),dp(trayMode?14:28));list.setVerticalScrollBarEnabled(false);list.setSelector(android.R.color.transparent);
        header=Ui.column(this);list.addHeaderView(header,null,false);adapter=new Cards();list.setAdapter(adapter);shell.addView(list,new LinearLayout.LayoutParams(-1,0,1));addNowPlaying(shell);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){filter();}public void afterTextChanged(Editable e){}});
        if(foreground)startIndexer();
        if(recent){cancelListing();populateRecent();rebuildHeader();crawlLibrary();}
        else if(history){cancelListing();populateHistory();rebuildHeader();}
        else{rebuildHeader();browse(directory);}
    }
    /** Ten-foot chrome: the folders of the directory we are browsing live in the left tray, Plex style. */
    private void rebuildTray(){
        if(trayFolders==null)return;trayFolders.removeAllViews();
        if(recent){trayFolders.addView(Ui.label(this,"NEWEST FIRST"),margins(-1,-2,17,12,14,8));return;}
        if(history){trayFolders.addView(Ui.label(this,"PLAYBACK HISTORY"),margins(-1,-2,17,12,14,8));return;}
        trayFolders.addView(Ui.label(this,"FOLDERS"),margins(-1,-2,17,12,14,8));
        if(directory!=null&&directory.getPathSegments().size()>1)trayFolders.addView(trayItem("back","Up one level",false,this::up));
        int shown=0;
        for(Entry e:visible)if(e.folder){trayFolders.addView(trayItem("folder",folderName(e.uri),false,()->{search.setText("");browse(e.uri);}));shown++;}
        if(shown==0){TextView none=Ui.text(this,"No folders here",12,Ui.MUTED);trayFolders.addView(none,margins(-1,-2,17,4,14,8));}
    }
    private View trayItem(String icon,String label,boolean active,Runnable action){
        LinearLayout item=Ui.row(this);item.setPadding(dp(11),dp(11),dp(10),dp(11));item.setClickable(true);item.setFocusable(true);
        if(active)item.setBackground(Ui.background(0x26f38bcb,0,this));
        View bar=new View(this);bar.setBackgroundColor(active?Ui.ACCENT:Color.TRANSPARENT);item.addView(bar,new LinearLayout.LayoutParams(dp(3),dp(20)));
        ImageView glyph=new ImageView(this);glyph.setImageDrawable(new Ui.Icon(icon,active?Ui.ACCENT:Ui.MUTED));item.addView(glyph,margins(dp(20),dp(20),10,0,0,0));
        TextView name=Ui.text(this,label,14,active?Ui.INK:Ui.MUTED);name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if(active)name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        item.addView(name,margins(0,-2,11,0,0,0));((LinearLayout.LayoutParams)name.getLayoutParams()).weight=1;
        Ui.focusRing(item,10);item.setContentDescription(label);item.setOnClickListener(v->action.run());
        return item;
    }
    /** Card widths are measured against the pane beside the tray, not the whole panel. */
    private int gridWidthDp(){
        int width=getResources().getConfiguration().screenWidthDp;
        return trayMode?width-TRAY-1-2*Math.round(Ui.overscanX(this)/getResources().getDisplayMetrics().density):width;
    }
    private void rebuildHeader(){
        if(header==null||!browsing)return;header.removeAllViews();
        if(!trayMode)header.addView(Ui.label(this,history?"YOUR VIEWING JOURNAL":"YOUR PRIVATE CINEMA"),margins(-1,-2,0,10,0,7));
        String name=recent?"Recently added":history?"Recently watched":folderName(directory);if(name==null||name.isEmpty())name="Your library";
        header.addView(display(name,trayMode?24:getResources().getConfiguration().screenWidthDp>=600?27:34),margins(-1,-2,0,trayMode?6:0,0,trayMode?2:5));
        if(recent){header.addView(Ui.text(this,"The newest episodes on your NAS.",13,Ui.MUTED),margins(-1,-2,0,0,0,18));}
        else if(history){header.addView(Ui.text(this,"Pick up a story. Or revisit a favorite.",13,Ui.MUTED),margins(-1,-2,0,0,0,18));}
        else{
            LinearLayout path=Ui.row(this);if(!trayMode){Button back=Ui.button(this,"‹",this::up);back.setTextSize(23);path.addView(back,new LinearLayout.LayoutParams(dp(42),dp(38)));}
            location=Ui.text(this,directory.getPath(),12,Ui.MUTED);location.setMaxLines(1);location.setEllipsize(android.text.TextUtils.TruncateAt.START);path.addView(location,margins(0,-2,10,0,8,0));((LinearLayout.LayoutParams)location.getLayoutParams()).weight=1;
            path.addView(Ui.iconButton(this,"refresh","Refresh folder",()->browse(directory)),new LinearLayout.LayoutParams(dp(38),dp(38)));header.addView(path,margins(-1,-2,0,trayMode?0:4,0,trayMode?6:14));
            addShowHeader();
            ArrayList<LibraryStore.Item> continueWatching=store.recent(profile,true,8);
            if(!continueWatching.isEmpty()&&directory.equals(profile.start())){
                LinearLayout section=Ui.row(this);section.addView(Ui.heading(this,"Continue watching",18),new LinearLayout.LayoutParams(0,-2,1));section.addView(Ui.text(this,Integer.toString(continueWatching.size()),12,Ui.ACCENT));header.addView(section,margins(-1,-2,0,8,0,12));
                HorizontalScrollView rail=new HorizontalScrollView(this);rail.setHorizontalScrollBarEnabled(false);rail.setClipToPadding(false);LinearLayout cards=Ui.row(this);cards.setGravity(Gravity.TOP);
                for(LibraryStore.Item item:continueWatching){View card=videoCard(item,235,true);cards.addView(card,margins(dp(235),-2,0,0,12,0));}
                rail.addView(cards);header.addView(rail,margins(-1,-2,0,0,0,24));
            }
        }
        LinearLayout section=Ui.row(this);section.addView(trayMode?Ui.label(this,recent?"RECENTLY ADDED":history?"PLAYBACK HISTORY":"IN THIS FOLDER"):Ui.heading(this,recent?"Recently added":history?"Playback history":"In this folder",18),new LinearLayout.LayoutParams(0,-2,1));status=Ui.text(this,"",11,Ui.MUTED);section.addView(status);header.addView(section,margins(-1,-2,0,trayMode?4:3,0,trayMode?9:16));updateStatus();
    }
    private ArrayList<Uri> showEntries(boolean folders){ArrayList<Uri> result=new ArrayList<>();for(Entry e:entries)if(e.folder==folders)result.add(e.uri);return result;}
    private void loadShowMetadata(int id,String query,boolean searchOnly){
        if(history||directory==null)return;
        ArrayList<Uri> folders=showEntries(true),videos=showEntries(false);
        // Season folders carry their show's ID for artwork, but the show and its sidecar live one level up.
        Uri inferred=directory.getPathSegments().size()>=2&&ShowMetadata.season(directory)?ShowMetadata.parent(profile,directory)
            :store.item(directory).metadata.optInt("tvmazeShow")>0?directory:matchedAncestor(directory);
        if(inferred==null)inferred=ShowMetadata.root(profile,directory,folders,videos);
        if(inferred==null&&(searchOnly||id>0))inferred=directory;
        if(inferred==null){rebuildHeader();return;}
        showRoot=inferred;showStatus=searchOnly?"Searching TVmaze…":"Loading show information…";rebuildHeader();
        final Uri requested=directory;final int ticket=generation;
        shows.load(profile,showRoot,directory,folders,videos,id,query,searchOnly,result->{
            if(isDestroyed()||!browsing||history||ticket!=generation||!requested.equals(directory))return;
            showResult=result;showStatus=result.status;showRoot=result.root;filter();rebuildHeader();refreshDetails();
            if(searchOnly&&foreground)chooseShow(result);
        });
    }
    /** The nearest folder above this one that already carries a chosen show, so seasons and
     *  loose subfolders keep the match instead of looking like unmatched folders. */
    /** Folders read as what we detected them to be: the matched show, or the season it holds,
     *  rather than the release directory it happens to be named after. */
    private String folderName(Uri uri){
        LibraryStore.Item item=store.item(uri);
        String season=ShowMetadata.seasonName(uri.getLastPathSegment());
        if(season!=null)return season;
        String show=item.metadata.optString("tvmazeShowName").trim();
        return show.isEmpty()?item.title():show;
    }
    private Uri matchedAncestor(Uri folder){
        Uri current=folder;
        while(current!=null&&current.getPathSegments().size()>1){
            current=ShowMetadata.parent(profile,current);
            if(!profile.contains(current))return null;
            if(store.item(current).metadata.optInt("tvmazeShow")>0)return current;
        }
        return null;
    }
    private void addShowHeader(){
        LibraryStore.Item item=store.item(showRoot==null?directory:showRoot);JSONObject m=item.metadata;
        if(m.optInt("tvmazeShow")>0){
            LinearLayout panel=Ui.row(this);panel.setGravity(Gravity.TOP);ArtworkView poster=new ArtworkView(this);poster.bind(item);panel.addView(poster,new LinearLayout.LayoutParams(dp(78),dp(116)));
            LinearLayout words=Ui.column(this);TextView show=Ui.heading(this,m.optString("tvmazeShowName"),17);words.addView(show);
            TextView genre=Ui.text(this,m.optString("tvmazeGenre")+" · "+m.optString("tvmazeStatus"),11,Ui.ACCENT);genre.setMaxLines(2);words.addView(genre,margins(-1,-2,0,5,0,7));
            JSONObject rootData=store.item(showRoot==null?directory:showRoot).metadata;TextView plot=Ui.text(this,rootData.optString("tvmazeDescription",m.optString("tvmazeDescription")),12,Ui.MUTED);plot.setMaxLines(4);plot.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(plot);panel.addView(words,margins(0,-2,13,0,0,0));((LinearLayout.LayoutParams)words.getLayoutParams()).weight=1;header.addView(panel,margins(-1,-2,0,0,0,10));
        }
        if(directory.getPathSegments().size()<2)return;
        // Category folders (Library, Shows, Movies) and single-movie folders are not shows: no match prompt.
        if(m.optInt("tvmazeShow")<=0&&showRoot==null&&showEntries(false).size()<2)return;
        LinearLayout actions=Ui.row(this);
        Button match=Ui.button(this,m.optInt("tvmazeShow")>0?"Show info":"Match show",this::showInfo);actions.addView(match,new LinearLayout.LayoutParams(-2,dp(38)));
        TextView state=Ui.text(this,showStatus,10,Ui.MUTED);state.setMaxLines(2);actions.addView(state,margins(0,-2,10,0,0,0));((LinearLayout.LayoutParams)state.getLayoutParams()).weight=1;header.addView(actions,margins(-1,-2,0,0,0,18));
    }
    private void showInfo(){
        if(showResult==null||showResult.bundle==null){if(showResult!=null&&showResult.candidates!=null&&showResult.candidates.length()>0)chooseShow(showResult);else searchShow();return;}
        JSONObject show=showResult.bundle.optJSONObject("show");ScrollView scroll=new ScrollView(this);LinearLayout body=Ui.column(this);body.setPadding(dp(22),dp(12),dp(22),dp(20));scroll.addView(body);
        body.addView(Ui.text(this,ShowMetadata.label(show),17,Ui.INK));body.addView(Ui.text(this,ShowMetadata.plain(ShowMetadata.value(show,"summary")),14,Ui.INK),margins(-1,-2,0,12,0,12));
        body.addView(Ui.text(this,showStatus+"\n"+showRoot.getPath()+"/.justplay/",12,Ui.MUTED));
        body.addView(Ui.button(this,"Metadata from TVmaze ↗",()->openMetadataSource(ShowMetadata.value(show,"url"))),margins(-1,dp(44),0,12,0,0));
        new AlertDialog.Builder(this).setTitle("Show information").setView(scroll).setNeutralButton("Change match",(d,w)->searchShow()).setNegativeButton("Close",null).setPositiveButton("Refresh info",(d,w)->loadShowMetadata(show.optInt("id"),null,false)).show();
    }
    private void searchShow(){
        EditText query=new EditText(this);query.setSingleLine();Ui.focusRing(query,10);Uri root=showRoot==null?directory:showRoot;query.setText(ShowMetadata.query(root));
        new AlertDialog.Builder(this).setTitle("Match this show").setMessage("Search by the show's title. The selected match is saved beside its folder.").setView(query).setNegativeButton("Cancel",null).setPositiveButton("Search",(d,w)->{String text=query.getText().toString().trim();if(!text.isEmpty())loadShowMetadata(0,text,true);}).show();
    }
    private void chooseShow(ShowMetadata.Result result){
        if(result.candidates==null||result.candidates.length()==0){Toast.makeText(this,result.status,Toast.LENGTH_LONG).show();return;}
        JSONArray hits=result.candidates;int count=Math.min(20,hits.length());String[] names=new String[count];for(int i=0;i<count;i++)names[i]=ShowMetadata.label(hits.optJSONObject(i).optJSONObject("show"));
        new AlertDialog.Builder(this).setTitle("Choose this folder's show").setItems(names,(d,n)->loadShowMetadata(hits.optJSONObject(n).optJSONObject("show").optInt("id"),null,false)).setNeutralButton("Search again",(d,w)->searchShow()).setNegativeButton("Cancel",null).show();
    }
    private void openMetadataSource(String address){Uri uri=Uri.parse(address);if(!"https".equals(uri.getScheme())||!"www.tvmaze.com".equals(uri.getHost()))return;try{startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(android.content.ActivityNotFoundException ignored){Toast.makeText(this,"No browser available",Toast.LENGTH_SHORT).show();}}
    private void updateStatus(){if(status==null)return;if(progress!=null&&progress.getVisibility()==View.VISIBLE)status.setText("Connecting…");else if(!history&&indexer.busy())status.setText("Adding previews…");else status.setText(visible.size()+ (history?" plays":" items"));}
    private void cancelListing(){generation++;handler.removeCallbacksAndMessages(null);if(listing!=null){listing.setEventListener(null);listing.release();listing=null;}}
    private void browse(Uri uri){
        if(!profile.contains(uri))return;cancelListing();indexer.stop();if(foreground)startIndexer();
        directory=uri;showRoot=null;showResult=null;showStatus="";getPreferences(MODE_PRIVATE).edit().putString(directoryPreference(),uri.toString()).apply();
        entries.clear();filter();rebuildHeader();progress.setVisibility(View.VISIBLE);updateStatus();
        if(!app.bindLocalNetwork()){progress.setVisibility(View.GONE);status.setText("Network unavailable");emptyNotice("Connect to your network","Your cached history is still available.");return;}
        int request=generation;Media media=app.media(profile,uri);listing=media;media.addOption(":ignore-filetypes=");
        media.setEventListener(event->{
            if(request!=generation||isDestroyed())return;
            if(event.type==IMedia.Event.ParsedChanged){handler.removeCallbacksAndMessages(null);progress.setVisibility(View.GONE);
                if(event.getParsedStatus()!=IMedia.ParsedStatus.Done){status.setText("Connection failed");emptyNotice("Couldn’t open this folder","Check your network and NAS settings, then refresh.");return;}
                MediaList children=media.subItems();try{for(int i=0;i<children.getCount();i++){IMedia child=children.getMediaAt(i);try{Uri u=child.getUri();if(profile.contains(u)){boolean folder=child.getType()==IMedia.Type.Directory;if(!".justplay".equalsIgnoreCase(u.getLastPathSegment())&&(folder||isMedia(u)))entries.add(new Entry(u,folder));}}finally{child.release();}}}finally{children.release();}
                filter();updateStatus();loadAddedTimes(uri,request);loadShowMetadata(0,null,false);if(entries.isEmpty())emptyNotice("Nothing to play here yet","Open another folder to find your videos.");
            }
        });
        if(!media.parseAsync(IMedia.Parse.ParseNetwork,25000)){progress.setVisibility(View.GONE);emptyNotice("Couldn’t connect","Refresh the folder to try again.");}
        handler.postDelayed(()->{if(request==generation&&progress.getVisibility()==View.VISIBLE){cancelListing();progress.setVisibility(View.GONE);emptyNotice("The NAS is taking a while","Check the connection and refresh this folder.");}},27000);
    }
    static boolean isMedia(Uri uri){String n=uri.getLastPathSegment();return n!=null&&!n.startsWith(".")&&!n.matches("(?i).*\\.(?:jpg|jpeg|png|webp|gif|nfo|txt|json|xml|srt|ass|ssa|sub|idx|vtt|db|url|md|pdf)$");}
    private void populateRecent(){
        entries.clear();
        for(LibraryStore.Item i:store.added(profile,400))if(isMedia(i.uri)&&entries.size()<120)entries.add(new Entry(i.uri,false));
        filter();
    }
    /**
     * What is new cannot come from folders the library happens to have opened, so the share is
     * walked once per visit: one connection, three levels down from the starting folder.
     */
    private void crawlLibrary(){
        if(crawling||System.currentTimeMillis()-lastCrawl<60000)return;
        crawling=true;if(progress!=null)progress.setVisibility(View.VISIBLE);
        final Profile snapshot=profile;final Uri root=profile.start();
        nasWorker.execute(()->{
            final java.util.LinkedHashMap<Uri,Long> files=new java.util.LinkedHashMap<>();
            final java.util.LinkedHashMap<Uri,Long> folders=new java.util.LinkedHashMap<>();
            try(NasSidecar nas=new NasSidecar(snapshot)){
                nas.walk(nas.path(root),3,4000,new NasSidecar.Tree(){
                    public boolean stale(String path,long modified){
                        Uri uri=nasUri(snapshot,path);
                        return uri!=null&&store.scanned(uri)!=modified;
                    }
                    public void file(String path,long modified){
                        Uri uri=nasUri(snapshot,path);
                        if(uri!=null&&isMedia(uri))files.put(uri,modified);
                    }
                    public void scanned(String path,long modified){
                        Uri uri=nasUri(snapshot,path);
                        if(uri!=null)folders.put(uri,modified);
                    }
                });
            }catch(Exception e){android.util.Log.w("JPlay","library walk failed",e);files.clear();folders.clear();}
            handler.post(()->{
                crawling=false;lastCrawl=System.currentTimeMillis();
                android.util.Log.i("JPlay","library walk: "+files.size()+" files, "+folders.size()+" folders read");
                if(isDestroyed())return;
                if(progress!=null)progress.setVisibility(View.GONE);
                for(java.util.Map.Entry<Uri,Long> file:files.entrySet())store.markAdded(file.getKey(),file.getValue(),false);
                for(java.util.Map.Entry<Uri,Long> folder:folders.entrySet()){store.markAdded(folder.getKey(),folder.getValue(),true);store.markScanned(folder.getKey(),folder.getValue());}
                if(browsing&&recent&&!files.isEmpty()){populateRecent();rebuildHeader();updateStatus();}
            });
        });
    }
    private static Uri nasUri(Profile profile,String path){
        Uri.Builder b=profile.root().buildUpon();
        for(String part:path.split("\\\\"))if(!part.isEmpty())b.appendPath(part);
        Uri uri=b.build();
        return profile.contains(uri)?uri:null;
    }
    private void populateHistory(){entries.clear();for(LibraryStore.Item i:store.history(profile,250)){Entry e=new Entry(i.uri,false);e.historyItem=i;entries.add(e);}filter();}
    /**
     * Newest first needs a date the NAS listing never supplies, so the SMB folder is asked
     * once per browse and each child keeps its own timestamp in the catalogue.
     */
    private void loadAddedTimes(Uri folder,int request){
        if(recent)return;
        if(entries.isEmpty()||folder.getPathSegments().size()<2)return;
        final ArrayList<Uri> children=new ArrayList<>();final java.util.HashSet<Uri> folders=new java.util.HashSet<>();
        for(Entry e:entries){children.add(e.uri);if(e.folder)folders.add(e.uri);}
        final Profile snapshot=profile;
        nasWorker.execute(()->{
            java.util.HashMap<String,Long> times;
            try(NasSidecar nas=new NasSidecar(snapshot,folder)){times=nas.modified();}
            catch(Exception e){return;}
            handler.post(()->{
                if(isDestroyed()||request!=generation||!folder.equals(directory))return;
                boolean changed=false;
                for(Uri child:children){
                    Long when=times.get(child.getLastPathSegment());
                    if(when==null||when<=0)continue;
                    if(store.item(child).metadata.optLong("added")!=when){store.markAdded(child,when,folders.contains(child));changed=true;}
                }
                Long stamp=times.get(folder.getLastPathSegment());
                if(stamp!=null)store.markScanned(folder,stamp);
                if(changed&&browsing)filter();
            });
        });
    }
    private void filter(){
        visible.clear();String query=search==null?"":search.getText().toString().trim().toLowerCase(Locale.ROOT);
        for(Entry e:entries)if(e.name.toLowerCase(Locale.ROOT).contains(query)||(!e.folder&&store.item(e.uri).title().toLowerCase(Locale.ROOT).contains(query)))visible.add(e);
        if(!history&&!recent)Collections.sort(visible,(a,b)->{if(a.folder!=b.folder)return a.folder?-1:1;
            if(a.folder&&b.folder){int x=ShowMetadata.seasonRank(a.uri),y=ShowMetadata.seasonRank(b.uri);if(x>=0&&y>=0&&x!=y)return Integer.compare(x,y);}LibraryStore.Item ai=store.item(a.uri),bi=store.item(b.uri);if(sortMode==4&&!a.folder&&!b.folder){
                int[] x=ShowMetadata.episodeNumber(a.uri),y=ShowMetadata.episodeNumber(b.uri);
                if(x!=null&&y!=null){int season=Integer.compare(x[0],y[0]);if(season!=0)return season;int episode=Integer.compare(x[1],y[1]);if(episode!=0)return episode;}
            }
            int order=sortMode==1?Long.compare(bi.lastPlayed,ai.lastPlayed):sortMode==2?Long.compare(bi.duration,ai.duration):sortMode==3?Integer.compare(bi.metadata.optInt("width"),ai.metadata.optInt("width")):sortMode==4?Long.compare(bi.metadata.optLong("added"),ai.metadata.optLong("added")):0;return order!=0?order:ai.title().compareToIgnoreCase(bi.title());});
        rows.clear();
        if(trayMode){ArrayList<Entry> rail=new ArrayList<>(visible);if(recent)for(java.util.Iterator<Entry> it=rail.iterator();it.hasNext();)if(it.next().folder)it.remove();if(!rail.isEmpty())rows.add(rail);refreshRows();updateStatus();rebuildTray();return;}
        ArrayList<Entry> row=new ArrayList<>();for(Entry e:visible){if(e.folder&&trayMode&&!history)continue;if(e.folder||history){if(!row.isEmpty()){rows.add(row);row=new ArrayList<>();}ArrayList<Entry> single=new ArrayList<>();single.add(e);rows.add(single);}else{row.add(e);if(row.size()==columns){rows.add(row);row=new ArrayList<>();}}}if(!row.isEmpty())rows.add(row);
        refreshRows();updateStatus();rebuildTray();
    }
    private void emptyNotice(String title,String subtitle){if(header==null)return;LinearLayout empty=Ui.column(this);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(36),dp(24),dp(36));empty.addView(display(title,24));TextView note=Ui.text(this,subtitle,13,Ui.MUTED);note.setGravity(Gravity.CENTER);empty.addView(note,margins(-1,-2,0,10,0,0));header.addView(empty);}
    private void up(){if(directory==null||directory.getPathSegments().size()<=1)return;Uri.Builder b=profile.root().buildUpon();java.util.List<String> parts=directory.getPathSegments();for(int i=1;i<parts.size()-1;i++)b.appendPath(parts.get(i));search.setText("");browse(b.build());}
    private void sort(){String[] names={"Title · A to Z","Recently played","Longest first","Highest resolution","Recently added"};new AlertDialog.Builder(this).setTitle("Sort library").setSingleChoiceItems(names,sortMode,(dialog,which)->{sortMode=which;getPreferences(MODE_PRIVATE).edit().putInt("sort",which).apply();filter();dialog.dismiss();}).setNegativeButton("Cancel",null).show();}
    private void historyActions(){new AlertDialog.Builder(this).setTitle("Watch history").setMessage("Clear watch history and saved resume positions on this phone? Your videos and metadata stay available.").setNegativeButton("Keep history",null).setPositiveButton("Clear history",(d,w)->{store.clearHistory();populateHistory();rebuildHeader();}).show();}
    private View videoCard(LibraryStore.Item item,int widthDp,boolean resume){
        String label=item.title();
        LinearLayout card=Ui.column(this);card.setOnClickListener(v->details(item.uri));card.setContentDescription(label+", "+item.summary()+", video details");Ui.focusRing(card,14);card.setTag("card:"+item.uri);trackFocus(card);
        FrameLayout image=new FrameLayout(this);ArtworkView art=new ArtworkView(this);art.bind(item);image.addView(art,new FrameLayout.LayoutParams(-1,-1));
        ImageButton play=Ui.iconButton(this,"play",(resume?"Resume ":"Play ")+label,()->play(item.uri,-1));play.setTag("play:"+item.uri);trackFocus(play);play.setImageDrawable(new Ui.Icon("play",Ui.BG));play.setBackground(Ui.focusable(this,Ui.background(Ui.ACCENT,30,this),Ui.ACCENT,30));FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(dp(36),dp(36),Gravity.BOTTOM|Gravity.RIGHT);pp.setMargins(0,0,dp(9),dp(9));image.addView(play,pp);card.addView(image,new LinearLayout.LayoutParams(-1,dp(Math.round(widthDp*9f/16f))));
        TextView title=Ui.heading(this,label,15);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(title,margins(-1,-2,0,10,0,4));
        String line=resume?"Resume at "+Ui.time(item.position):item.summary();if(line.isEmpty())line="Video · details available on scan";
        TextView sub=Ui.text(this,line,11,resume?Ui.ACCENT:Ui.MUTED);sub.setMaxLines(1);sub.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(sub);
        if(foreground)indexer.request(item.uri);
        return card;
    }
    /** A folder as a title card: same 16:9 frame as an episode, opening the folder instead of playing. */
    private View folderTile(Entry e,int widthDp){
        LibraryStore.Item item=store.item(e.uri);String label=folderName(e.uri);
        LinearLayout card=Ui.column(this);card.setTag("card:"+e.uri);trackFocus(card);Ui.focusRing(card,14);
        card.setContentDescription(label+", folder");card.setOnClickListener(v->{search.setText("");browse(e.uri);});
        FrameLayout image=new FrameLayout(this);ArtworkView art=new ArtworkView(this);art.bind(item);image.addView(art,new FrameLayout.LayoutParams(-1,-1));
        ImageView glyph=new ImageView(this);glyph.setImageDrawable(new Ui.Icon("folder",Ui.INK));
        FrameLayout.LayoutParams gp=new FrameLayout.LayoutParams(dp(30),dp(30),Gravity.BOTTOM|Gravity.LEFT);gp.setMargins(dp(11),0,0,dp(11));image.addView(glyph,gp);
        card.addView(image,new LinearLayout.LayoutParams(-1,dp(Math.round(widthDp*9f/16f))));
        TextView title=Ui.heading(this,label,15);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(title,margins(-1,-2,0,10,0,4));
        String note=item.metadata.optString("tvmazeGenre").trim();
        if(note.isEmpty()){Uri above=matchedAncestor(e.uri);note=above==null?"Folder":store.item(above).metadata.optString("tvmazeShowName","Folder");}
        TextView sub=Ui.text(this,note,11,Ui.MUTED);sub.setMaxLines(1);sub.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(sub);
        return card;
    }
    private View folderCard(Entry e){LinearLayout card=Ui.row(this);card.setTag("card:"+e.uri);trackFocus(card);card.setPadding(dp(15),dp(16),dp(12),dp(16));card.setBackground(Ui.background(Ui.CARD,14,this));Ui.focusRing(card,14);LibraryStore.Item item=store.item(e.uri);if(item.metadata.optInt("tvmazeShow")>0){ArtworkView art=new ArtworkView(this);art.bind(item);card.addView(art,new LinearLayout.LayoutParams(dp(48),dp(70)));}else{ImageView icon=new ImageView(this);icon.setImageDrawable(new Ui.Icon("folder",Ui.ACCENT));card.addView(icon,new LinearLayout.LayoutParams(dp(26),dp(26)));}LinearLayout words=Ui.column(this);words.addView(Ui.heading(this,store.item(e.uri).title(),16));words.addView(Ui.text(this,item.metadata.optString("tvmazeGenre","Folder"),11,Ui.MUTED));card.addView(words,margins(0,-2,14,0,0,0));((LinearLayout.LayoutParams)words.getLayoutParams()).weight=1;card.addView(Ui.text(this,"›",23,Ui.MUTED));card.setOnClickListener(v->{search.setText("");browse(e.uri);});return card;}
    private View historyCard(Entry e){LibraryStore.Item item=e.historyItem;String label=item.title();LinearLayout row=Ui.row(this);row.setGravity(Gravity.CENTER_VERTICAL);ArtworkView art=new ArtworkView(this);art.bind(store.item(e.uri));row.addView(art,new LinearLayout.LayoutParams(dp(112),dp(72)));LinearLayout words=Ui.column(this);TextView title=Ui.heading(this,label,15);title.setMaxLines(2);words.addView(title);words.addView(Ui.text(this,android.text.format.DateUtils.getRelativeTimeSpanString(item.lastPlayed,System.currentTimeMillis(),60000).toString(),11,Ui.MUTED));words.addView(Ui.text(this,item.completed?"Watched": "Stopped at "+Ui.time(item.position),11,Ui.ACCENT));row.addView(words,margins(0,-2,13,0,8,0));((LinearLayout.LayoutParams)words.getLayoutParams()).weight=1;row.setOnClickListener(v->details(e.uri));Ui.focusRing(row,14);row.setTag("card:"+e.uri);trackFocus(row);return row;}
    /**
     * Ten-foot rail: title cards scroll sideways, four to a screen, so the remote walks right
     * through a folder instead of paging a grid downwards.
     */
    private View rail(ArrayList<Entry> row,LinearLayout holder){
        int width=Math.max(150,(gridWidthDp()-40-(columns-1)*14)/columns);
        HorizontalScrollView scroller=new HorizontalScrollView(this);scroller.setHorizontalScrollBarEnabled(false);scroller.setClipToPadding(false);
        LinearLayout cards=Ui.row(this);cards.setGravity(Gravity.TOP);
        for(Entry e:row){
            if(e.folder){cards.addView(folderTile(e,width),margins(dp(width),-2,0,0,14,0));continue;}
            LibraryStore.Item item=history&&e.historyItem!=null?e.historyItem:store.item(e.uri);
            cards.addView(videoCard(item,width,history),margins(dp(width),-2,0,0,14,0));
        }
        scroller.addView(cards);holder.addView(scroller,new LinearLayout.LayoutParams(-1,-2));
        return holder;
    }
    private final class Cards extends BaseAdapter {
        public int getCount(){return rows.size();}public Object getItem(int p){return rows.get(p);}public long getItemId(int p){return p;}
        public View getView(int position,View reuse,ViewGroup parent){ArrayList<Entry> row=rows.get(position);LinearLayout holder=Ui.row(MainActivity.this);holder.setGravity(Gravity.TOP);holder.setPadding(0,0,0,dp(22));
            if(trayMode)return rail(row,holder);
            if(row.get(0).folder){holder.addView(folderCard(row.get(0)),new LinearLayout.LayoutParams(-1,-2));return holder;}if(history){holder.addView(historyCard(row.get(0)),new LinearLayout.LayoutParams(-1,-2));return holder;}
            int available=gridWidthDp()-40-(columns-1)*14;int width=available/columns;
            for(int n=0;n<columns;n++){View card=n<row.size()?videoCard(store.item(row.get(n).uri),width,false):new View(MainActivity.this);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(n>0)lp.leftMargin=dp(14);holder.addView(card,lp);}return holder;
        }
    }
    private void play(Uri uri,long start){
        if(!app.bindLocalNetwork()){Toast.makeText(this,"Connect to your network to play from your NAS",Toast.LENGTH_LONG).show();return;}
        indexer.stop();startActivity(new Intent(this,PlayerActivity.class).putExtra("uri",uri.toString()).putExtra("startMs",start));
    }
    private void details(Uri uri){
        LibraryStore.Item item=store.item(uri);Dialog dialog=new Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);detailsDialog=dialog;detailUri=uri;dialog.setOnDismissListener(d->{if(detailsDialog==dialog){detailsDialog=null;detailUri=null;}});
        if(trayMode){detailsScreen(uri,item,dialog);return;}
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout body=Ui.column(this);body.setPadding(dp(22),dp(18),dp(22),dp(24));body.setBackground(Ui.background(Ui.CARD,24,this));scroll.addView(body);dialog.setContentView(scroll);
        LinearLayout heading=Ui.row(this);heading.addView(Ui.label(this,"VIDEO DETAILS"),new LinearLayout.LayoutParams(0,-2,1));heading.addView(Ui.iconButton(this,"close","Close details",dialog::dismiss),new LinearLayout.LayoutParams(dp(38),dp(38)));body.addView(heading);
        ArtworkView art=new ArtworkView(this);art.bind(item);detailArt=art;body.addView(art,margins(-1,dp(190),0,12,0,18));detailTitle=display(item.title(),29);body.addView(detailTitle);TextView summary=Ui.text(this,item.summary(),13,Ui.MUTED);detailSummary=summary;body.addView(summary,margins(-1,-2,0,7,0,18));
        LinearLayout actions=Ui.row(this);Button main=Ui.primary(this,item.position>0&&!item.completed?"Resume · "+Ui.time(item.position):"Play video",()->{dialog.dismiss();play(uri,-1);});main.post(main::requestFocus);actions.addView(main,new LinearLayout.LayoutParams(0,dp(48),1));if(item.position>0){Button restart=Ui.button(this,"Start over",()->{dialog.dismiss();play(uri,0);});actions.addView(restart,margins(-2,dp(48),9,0,0,0));}body.addView(actions);
        if(item.duration>0&&item.position>0){ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(1000);bar.setProgress((int)(item.progress()*1000));bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));body.addView(bar,margins(-1,dp(3),0,14,0,0));}
        JSONObject m=item.metadata;detailDescription=Ui.text(this,"",14,Ui.INK);body.addView(detailDescription,margins(-1,-2,0,20,0,0));
        detailVideo=metadataRow(body,"VIDEO","");detailAudio=metadataRow(body,"AUDIO","");detailSubs=metadataRow(body,"EMBEDDED SUBTITLES","");refreshDetails();
        if(m.optInt("tvmazeShow")>0){metadataRow(body,"SHOW",m.optString("tvmazeShowName"));if(!m.optString("tvmazeDate").isEmpty())metadataRow(body,"AIRED",m.optString("tvmazeDate"));if(!m.optString("tvmazeRating").isEmpty())metadataRow(body,"TVMAZE RATING",m.optString("tvmazeRating")+" / 10");body.addView(Ui.button(this,"Metadata from TVmaze ↗",()->openMetadataSource(m.optString("tvmazeUrl"))),margins(-1,dp(42),0,14,0,0));}
        String genre=m.optString("tvmazeGenre",m.optString("genre"));if(!genre.isEmpty())metadataRow(body,"GENRE",genre);if(!m.optString("director").isEmpty())metadataRow(body,"DIRECTOR",m.optString("director"));
        metadataRow(body,"FILE",item.filename);metadataRow(body,"LOCATION",uri.getPath());
        LinearLayout tools=Ui.row(this);tools.addView(Ui.button(this,item.completed?"Mark unwatched":"Mark watched",()->{store.markWatched(uri,!item.completed);dialog.dismiss();filter();rebuildHeader();}),new LinearLayout.LayoutParams(0,dp(46),1));tools.addView(Ui.button(this,"Refresh details",()->{indexer.refresh(uri);dialog.dismiss();Toast.makeText(this,"Refreshing metadata and preview",Toast.LENGTH_SHORT).show();}),margins(-2,dp(46),8,0,0,0));body.addView(tools,margins(-1,-2,0,20,0,0));
        dialog.show();Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setGravity(Gravity.BOTTOM);window.setLayout(-1,(int)(getResources().getDisplayMetrics().heightPixels*.88));}
    }
    /** Rebuilds the rows without losing the D-pad cursor: card views are recreated on every refresh. */
    /**
     * getView builds fresh card views, and ListView's detach/reattach leaves the old view
     * believing it is still focused, which strands the D-pad cursor on a card that no longer
     * receives keys. Drop focus deliberately across the rebuild, then put it back by tag.
     */
    private void refreshRows(){
        if(adapter==null)return;
        boolean hadFocus=list.hasFocus();
        if(hadFocus&&focusedCard!=null){
            restoreFocusTag=focusedCard;
            // A card that stays scrolled out is never rebuilt; do not let the request linger.
            handler.postDelayed(()->{if(restoreFocusTag!=null)restoreFocusTag=null;},1500);
        }
        if(hadFocus)list.clearFocus();
        adapter.notifyDataSetChanged();
    }
    /** Indexing reports one file at a time; coalesce those into one rebuild. */
    private void refreshRowsSoon(){
        if(rowRefreshPending)return;
        rowRefreshPending=true;
        handler.postDelayed(()->{rowRefreshPending=false;if(browsing)refreshRows();},600);
    }
    private void trackFocus(View v){
        v.setOnFocusChangeListener((view,has)->{if(has&&view.getTag() instanceof String)focusedCard=(String)view.getTag();});
        if(restoreFocusTag!=null&&restoreFocusTag.equals(v.getTag())){
            restoreFocusTag=null; final View target=v; v.post(target::requestFocus);
        }
    }
    private void refreshArtwork(View view,Uri uri){if(view instanceof ArtworkView)((ArtworkView)view).refresh(uri);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++)refreshArtwork(group.getChildAt(n),uri);}}
    private void refreshDetails(){
        if(detailUri==null||detailsDialog==null||detailVideo==null)return;LibraryStore.Item item=store.item(detailUri);JSONObject m=item.metadata;detailTitle.setText(item.title());detailArt.bind(item);detailSummary.setText(item.summary());
        String description=m.optString("tvmazeDescription",m.optString("description"));detailDescription.setText(description);detailDescription.setVisibility(description.isEmpty()?View.GONE:View.VISIBLE);
        detailVideo.setText(m.optInt("width")>0?m.optInt("width")+" × "+m.optInt("height")+"  ·  "+m.optString("videoCodec").toUpperCase(Locale.ROOT)+(m.optDouble("fps")>0?String.format(Locale.ROOT,"  ·  %.3f fps",m.optDouble("fps")):""):(item.indexed>0?"No video details found":"Reading file details…"));
        detailAudio.setText(m.has("audioCodec")?m.optString("audioCodec").toUpperCase(Locale.ROOT)+"  ·  "+m.optInt("channels")+" channels  ·  "+(m.optInt("sampleRate")/1000.0)+" kHz":(item.indexed>0?"No audio details found":"Reading file details…"));
        JSONArray tracks=m.optJSONArray("tracks");ArrayList<String> names=new ArrayList<>();if(tracks!=null)for(int n=0;n<tracks.length();n++){JSONObject t=tracks.optJSONObject(n);if(t!=null&&t.optInt("type")==IMedia.Track.Type.Text)names.add(t.optString("language").isEmpty()?t.optString("codec"):t.optString("language"));}detailSubs.setText(names.isEmpty()?(item.indexed>0?"None found":"Reading file details…"):android.text.TextUtils.join("  ·  ",names));
    }
    /**
     * The same details, laid out for a television: a still on the left at the size the panel
     * deserves, and everything you decide from - title, what it is, how far in you are, and
     * the play button - in one column on the right, with no scrolling to reach them.
     */
    private void detailsScreen(Uri uri,LibraryStore.Item item,Dialog dialog){
        JSONObject m=item.metadata;
        LinearLayout shell=Ui.row(this);shell.setGravity(Gravity.TOP);shell.setBackgroundColor(0xf7110c18);
        shell.setPadding(dp(38)+Ui.overscanX(this),dp(34)+Ui.overscanY(this),dp(38)+Ui.overscanX(this),dp(30)+Ui.overscanY(this));
        dialog.setContentView(shell);

        LinearLayout still=Ui.column(this);
        ArtworkView art=new ArtworkView(this);detailArt=art;art.bind(item);
        still.addView(art,new LinearLayout.LayoutParams(-1,dp(236)));
        if(item.duration>0&&item.position>0){
            ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);bar.setProgress((int)(item.progress()*1000));
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            still.addView(bar,margins(-1,dp(3),0,10,0,0));
            still.addView(Ui.text(this,Ui.time(item.position)+" of "+Ui.time(item.duration),11,Ui.MUTED),margins(-1,-2,0,8,0,0));
        }
        shell.addView(still,new LinearLayout.LayoutParams(dp(420),-2));

        LinearLayout words=Ui.column(this);
        String show=m.optString("tvmazeShowName").trim();
        words.addView(Ui.label(this,show.isEmpty()?"VIDEO DETAILS":show.toUpperCase(Locale.ROOT)),margins(-1,-2,0,2,0,8));
        detailTitle=display(item.title(),32);detailTitle.setMaxLines(2);detailTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(detailTitle);
        ArrayList<String> facts=new ArrayList<>();
        String aired=m.optString("tvmazeDate").trim();if(aired.length()>=10)facts.add("Aired "+aired.substring(0,10));
        String rating=m.optString("tvmazeRating").trim();if(!rating.isEmpty()&&!"null".equals(rating))facts.add(rating+" \u2605");
        if(!item.summary().isEmpty())facts.add(item.summary());
        detailSummary=Ui.text(this,android.text.TextUtils.join("  \u00b7  ",facts),13,Ui.MUTED);detailSummary.setMaxLines(1);
        words.addView(detailSummary,margins(-1,-2,0,9,0,14));
        detailDescription=Ui.text(this,"",14,Ui.INK);detailDescription.setMaxLines(5);detailDescription.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(detailDescription,margins(-1,-2,0,0,0,18));

        LinearLayout actions=Ui.row(this);
        Button main=Ui.primary(this,item.position>0&&!item.completed?"Resume \u00b7 "+Ui.time(item.position):"Play video",()->{dialog.dismiss();play(uri,-1);});
        main.post(main::requestFocus);actions.addView(main,new LinearLayout.LayoutParams(dp(230),dp(52)));
        if(item.position>0)actions.addView(Ui.button(this,"Start over",()->{dialog.dismiss();play(uri,0);}),margins(-2,dp(52),10,0,0,0));
        actions.addView(Ui.button(this,item.completed?"Mark unwatched":"Mark watched",()->{store.markWatched(uri,!item.completed);dialog.dismiss();filter();rebuildHeader();}),margins(-2,dp(52),10,0,0,0));
        actions.addView(Ui.button(this,"Refresh",()->{indexer.refresh(uri);dialog.dismiss();Toast.makeText(this,"Refreshing metadata and preview",Toast.LENGTH_SHORT).show();}),margins(-2,dp(52),10,0,0,0));
        words.addView(actions,margins(-1,-2,0,0,0,20));

        LinearLayout technical=Ui.row(this);technical.setGravity(Gravity.TOP);
        LinearLayout video=Ui.column(this),audio=Ui.column(this),subs=Ui.column(this);
        detailVideo=metadataRow(video,"VIDEO","");detailAudio=metadataRow(audio,"AUDIO","");detailSubs=metadataRow(subs,"SUBTITLES","");
        for(LinearLayout column:new LinearLayout[]{video,audio,subs}){
            ((ViewGroup.MarginLayoutParams)column.getChildAt(0).getLayoutParams()).topMargin=0;
            technical.addView(column,new LinearLayout.LayoutParams(0,-2,1));
        }
        words.addView(technical,margins(-1,-2,0,0,0,14));
        TextView file=Ui.text(this,item.filename,11,Ui.MUTED);file.setMaxLines(1);file.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);words.addView(file);
        if(m.optInt("tvmazeShow")>0&&!m.optString("tvmazeUrl").isEmpty())
            words.addView(Ui.button(this,"Metadata from TVmaze \u2197",()->openMetadataSource(m.optString("tvmazeUrl"))),margins(-2,dp(44),0,12,0,0));
        LinearLayout.LayoutParams wide=new LinearLayout.LayoutParams(0,-2,1);wide.leftMargin=dp(30);shell.addView(words,wide);

        refreshDetails();
        dialog.show();
        Window window=dialog.getWindow();
        if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setGravity(Gravity.CENTER);window.setLayout(-1,-1);}
    }
    private TextView metadataRow(LinearLayout body,String label,String value){body.addView(Ui.label(this,label),margins(-1,-2,0,19,0,6));TextView text=Ui.text(this,value,13,Ui.INK);text.setTextIsSelectable(true);body.addView(text);return text;}
    private EditText field(LinearLayout parent,String label,String value,boolean secret){parent.addView(Ui.label(this,label),margins(-1,-2,0,15,0,7));EditText e=new EditText(this);e.setText(value);e.setTextSize(15);e.setSingleLine();e.setTextColor(Ui.INK);e.setPadding(dp(13),0,dp(13),0);e.setBackground(Ui.background(Ui.CARD,11,this));e.setInputType(InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);Ui.focusRing(e,11);parent.addView(e,new LinearLayout.LayoutParams(-1,dp(50)));return e;}
    private void settings(){
        browsing=false;cancelListing();if(indexer!=null)indexer.stop();ScrollView scroll=new ScrollView(this);LinearLayout body=Ui.column(this);body.setPadding(dp(22),dp(16),dp(22),dp(32));body.setBackgroundColor(Ui.BG);scroll.addView(body);mount(scroll);
        LinearLayout top=Ui.row(this);if(!profile.host.isEmpty())top.addView(Ui.iconButton(this,"back","Back to library",()->library(directory==null?profile.start():directory)),new LinearLayout.LayoutParams(dp(44),dp(44)));top.addView(Ui.label(this,"  LIBRARY SETTINGS"));body.addView(top);body.addView(display("Make yourself\nat home.",34),margins(-1,-2,0,22,0,8));body.addView(Ui.text(this,"Connect your NAS. Everything stays yours.",14,Ui.MUTED));
        EditText host=field(body,"NAS ADDRESS",profile.host,false),share=field(body,"SHARE",profile.share,false),user=field(body,"USERNAME · BLANK FOR GUEST",profile.user,false),password=field(body,"PASSWORD",profile.password,true),domain=field(body,"DOMAIN · OPTIONAL",profile.domain,false),folder=field(body,"STARTING FOLDER",profile.folder,false),buffer=field(body,"NETWORK BUFFER · SECONDS (1–15)",Integer.toString(profile.bufferMs/1000),false);buffer.setInputType(InputType.TYPE_CLASS_NUMBER);
        TextView error=Ui.text(this,"",13,0xffffb4ab);body.addView(error,margins(-1,-2,0,10,0,8));body.addView(Ui.primary(this,"Save & open library",()->{
            String h=host.getText().toString().trim(),s=share.getText().toString().trim();if(h.isEmpty()||s.isEmpty()||h.matches(".*[/@ ?#].*")||s.contains("/")||s.equals(".")||s.equals("..")){error.setText("Enter a NAS hostname or IP and a single share name.");return;}int seconds;try{seconds=Integer.parseInt(buffer.getText().toString());}catch(Exception e){error.setText("Buffer must be between 1 and 15 seconds.");return;}if(seconds<1||seconds>15){error.setText("Buffer must be between 1 and 15 seconds.");return;}
            Profile p=new Profile();p.host=h;p.share=s;p.user=user.getText().toString();p.password=password.getText().toString();p.domain=domain.getText().toString().trim();p.folder=folder.getText().toString().trim();p.bufferMs=seconds*1000;if(!p.contains(p.start())){error.setText("The starting folder must stay inside the share.");return;}
            try{boolean sameStart=p.start().equals(profile.start());p.save(this);profile=p;history=false;library(sameStart?rememberedDirectory(null):p.start());}catch(Exception e){error.setText("Could not securely save this login.");}
        }),new LinearLayout.LayoutParams(-1,dp(51)));
        body.addView(Ui.label(this,"PREFERENCES"),margins(-1,-2,0,30,0,14));Switch thumbs=new Switch(this);thumbs.setText("Create video thumbnails");thumbs.setTextColor(Ui.INK);thumbs.setChecked(getPreferences(MODE_PRIVATE).getBoolean("thumbnails",true));thumbs.setOnCheckedChangeListener((button,checked)->getPreferences(MODE_PRIVATE).edit().putBoolean("thumbnails",checked).apply());Ui.focusRing(thumbs,8);body.addView(thumbs,new LinearLayout.LayoutParams(-1,dp(48)));body.addView(Ui.text(this,"Previews read a short section of each visible video over your network. They pause while you watch.",12,Ui.MUTED));
        EditText remote=field(body,"REMOTE CONTROL · COMPUTER IP",RemoteApi.controller(this),false);remote.setHint("Blank turns it off");remote.setHintTextColor(Ui.MUTED);TextView remoteNote=Ui.text(this,"The one computer allowed to send “play this” over your network.",12,Ui.MUTED);body.addView(remoteNote,margins(-1,-2,0,7,0,0));
        remote.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence t,int a,int b,int c){}public void onTextChanged(CharSequence t,int a,int b,int c){}public void afterTextChanged(android.text.Editable t){String v=t.toString().trim();if(RemoteApi.validController(v)){RemoteApi.setController(MainActivity.this,v);remoteNote.setText(v.isEmpty()?"Remote control is off.":"Only "+v+" can send “play this”.");}else remoteNote.setText("Enter an IP address, like the one your computer has on this network.");}});
        body.addView(Ui.button(this,"Crash reports → NAS",()->CrashReports.get(this).showSettings(this)),margins(-1,dp(49),0,20,0,10));body.addView(Ui.button(this,"Clear thumbnail cache",()->{store.clearThumbnails();Toast.makeText(this,"Thumbnails will regenerate as you browse",Toast.LENGTH_SHORT).show();}),margins(-1,dp(49),0,0,0,15));
        body.addView(Ui.text(this,"Login encrypted on this device. Local network streaming only.\nMetadata and history stay on your device.",12,Ui.MUTED));
    }
    @Override public void onBackPressed(){if(!browsing&&!profile.host.isEmpty())library(directory==null?profile.start():directory);else if(history){history=false;library(directory);}else if(browsing&&directory!=null&&directory.getPathSegments().size()>1)up();else super.onBackPressed();}
    private static final class Entry{final Uri uri;final boolean folder;final String name;LibraryStore.Item historyItem;Entry(Uri uri,boolean folder){this.uri=uri;this.folder=folder;name=uri.getLastPathSegment()==null?"Folder":uri.getLastPathSegment();}}
}
