package in.akuj.jplay;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.view.View;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Real cached video frames, with a quiet illustrated placeholder while indexing. */
final class ArtworkView extends View {
    private static final ExecutorService LOAD=Executors.newFixedThreadPool(2);
    private static final android.util.LruCache<String,Bitmap> CACHE=new android.util.LruCache<String,Bitmap>(12*1024*1024){protected int sizeOf(String key,Bitmap bitmap){return bitmap.getByteCount();}};
    private final Paint paint=new Paint(3);private final RectF rect=new RectF();private Bitmap bitmap;private String key="",title="";private int color=0xff354450;private float progress;private boolean watched;private Uri uri;
    ArtworkView(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    void bind(LibraryStore.Item item){
        uri=item.uri;
        title=item.title();progress=item.progress();watched=item.completed;
        int[] colors={0xff394650,0xff524139,0xff394f49,0xff4d3b43,0xff4d493c};color=colors[(item.uri.toString().hashCode()&0x7fffffff)%colors.length];
        File file=LibraryStore.get(getContext()).thumbnail(item.uri);String newKey=file.getPath()+":"+file.lastModified();
        if(!newKey.equals(key)){key=newKey;bitmap=CACHE.get(key);if(bitmap==null&&file.isFile()){final String wanted=key;LOAD.execute(()->{Bitmap decoded=BitmapFactory.decodeFile(file.getPath());if(decoded!=null){CACHE.put(wanted,decoded);post(()->{if(wanted.equals(key)){bitmap=decoded;invalidate();}});}});}}
        invalidate();
    }
    void refresh(Uri changed){if(changed.equals(uri))bind(LibraryStore.get(getContext()).item(uri));}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);float w=getWidth(),h=getHeight();rect.set(0,0,w,h);Path clip=new Path();clip.addRoundRect(rect,Ui.dp(getContext(),14),Ui.dp(getContext(),14),Path.Direction.CW);c.save();c.clipPath(clip);
        if(bitmap!=null){float scale=Math.max(w/bitmap.getWidth(),h/bitmap.getHeight());Matrix m=new Matrix();m.postScale(scale,scale);m.postTranslate((w-bitmap.getWidth()*scale)/2,(h-bitmap.getHeight()*scale)/2);paint.setShader(null);paint.setAlpha(255);c.drawBitmap(bitmap,m,paint);}
        else{paint.setShader(new LinearGradient(0,0,w,h,color,0xff181c21,Shader.TileMode.CLAMP));c.drawRect(rect,paint);paint.setShader(null);paint.setColor(0x18ffffff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);for(int n=0;n<4;n++)c.drawCircle(w*.8f,h*.1f,(n+1)*h*.34f,paint);paint.setStyle(Paint.Style.FILL);paint.setColor(0x55ffffff);paint.setTextSize(Ui.dp(getContext(),35));paint.setTypeface(Typeface.create("serif",Typeface.NORMAL));String letter=title.isEmpty()?"J":title.substring(0,1).toUpperCase(java.util.Locale.ROOT);c.drawText(letter,Ui.dp(getContext(),16),h-Ui.dp(getContext(),18),paint);}
        paint.setShader(new LinearGradient(0,h*.45f,0,h,0x00000000,0x88000000,Shader.TileMode.CLAMP));c.drawRect(rect,paint);paint.setShader(null);
        if(progress>0&&!watched){paint.setColor(0x66ffffff);c.drawRect(0,h-Ui.dp(getContext(),3),w,h,paint);paint.setColor(Ui.ACCENT);c.drawRect(0,h-Ui.dp(getContext(),3),w*progress,h,paint);}
        if(watched){paint.setColor(Ui.ACCENT);c.drawCircle(w-Ui.dp(getContext(),19),Ui.dp(getContext(),19),Ui.dp(getContext(),10),paint);paint.setColor(Ui.BG);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Ui.dp(getContext(),1));float x=w-Ui.dp(getContext(),23),y=Ui.dp(getContext(),19);c.drawLine(x,y,x+Ui.dp(getContext(),3),y+Ui.dp(getContext(),3),paint);c.drawLine(x+Ui.dp(getContext(),3),y+Ui.dp(getContext(),3),x+Ui.dp(getContext(),8),y-Ui.dp(getContext(),4),paint);paint.setStyle(Paint.Style.FILL);}
        c.restore();
    }
}
