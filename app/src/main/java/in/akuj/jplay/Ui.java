package in.akuj.jplay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = 0xff110c18, CARD = 0xff211929, INK = 0xfffaf2ff, MUTED = 0xffb8a9c5, ACCENT = 0xfff38bcb, LINE = 0xff372940;
    static int dp(Context c, int n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    static boolean tv(Context c) {
        android.app.UiModeManager modes = c.getSystemService(android.app.UiModeManager.class);
        return (modes != null && modes.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION)
            || c.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK);
    }
    /**
     * The logical width a television window is laid out against: a 1080p panel reports
     * 1920x1080 at xhdpi, which would leave only 960dp for a ten-foot layout and blow every
     * card up. Normalising to a fixed dp width keeps four title cards on the screen at a
     * readable size no matter what density the panel claims.
     */
    static final float TEN_FOOT_WIDTH=1152f;
    /**
     * Ten-foot scale. The TV hands the app a 1080p window at xhdpi, the same density as a
     * phone held at arm's length, and the panel upscales to 4K afterwards. Growing the
     * density grows every dp and sp in the app at once, so type and focus targets read from
     * a sofa without every layout carrying its own TV numbers.
     */
    static Context scaled(Context base){
        if(!tv(base))return base;
        android.content.res.Configuration c=new android.content.res.Configuration(base.getResources().getConfiguration());
        int width=Math.round(c.screenWidthDp*(c.densityDpi/160f));
        if(width>0)c.densityDpi=Math.max(160,Math.min(480,Math.round(160f*width/TEN_FOOT_WIDTH)));
        return base.createConfigurationContext(c);
    }
    /** Television overscan: keep content off the bezel-cropped edge of the panel. */
    static int overscanX(Context c){return tv(c)?dp(c,14):0;}
    static int overscanY(Context c){return tv(c)?dp(c,10):0;}
    /** A remote has no pointer, so focus has to be visible: an accent ring over the view's own content. */
    static void focusRing(android.view.View v, int radius) {
        GradientDrawable ring = background(Color.TRANSPARENT, radius, v.getContext());
        ring.setStroke(dp(v.getContext(), 3), ACCENT);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, ring);
        states.addState(new int[0], new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        v.setForeground(states);
        v.setDefaultFocusHighlightEnabled(false);
    }
    /** Buttons keep their touch ripple and gain a focused state for D-pad navigation. */
    static android.graphics.drawable.Drawable focusable(Context c, android.graphics.drawable.Drawable normal, int fill, int radius) {
        GradientDrawable focused = background(fill, radius, c);
        focused.setStroke(dp(c, 3), fill == ACCENT ? INK : ACCENT);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[0], normal);
        return states;
    }
    static GradientDrawable background(int color, int radius, Context c) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c, radius)); return d;
    }
    static TextView text(Context c, String value, int size, int color) {
        TextView t = new TextView(c); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setFontFeatureSettings("kern"); return t;
    }
    static TextView heading(Context c, String value, int size) {
        TextView t = text(c, value, size, INK); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return t;
    }
    static LinearLayout column(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static LinearLayout row(Context c) { LinearLayout l = new LinearLayout(c); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    static Button button(Context c, String value, Runnable action) {
        Button b = new Button(c); b.setText(value); b.setTextColor(ACCENT); b.setTextSize(14); b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setMinHeight(dp(c,48)); b.setMinimumWidth(0); b.setPadding(dp(c,14),0,dp(c,14),0);
        b.setBackground(focusable(c,new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33f38bcb),background(CARD,14,c),null),CARD,14));
        b.setOnClickListener(v -> action.run()); return b;
    }
    static TextView label(Context c,String value){TextView t=text(c,value,10,MUTED);t.setLetterSpacing(.16f);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    static TextView chip(Context c,String value){TextView t=text(c,value,11,INK);t.setPadding(dp(c,9),dp(c,5),dp(c,9),dp(c,5));t.setBackground(background(0xff34283f,7,c));return t;}
    static Button primary(Context c,String value,Runnable action){Button b=button(c,value,action);b.setTextColor(BG);b.setBackground(focusable(c,new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x44000000),background(ACCENT,14,c),null),ACCENT,14));return b;}
    static android.widget.ImageButton iconButton(Context c,String name,String description,Runnable action){android.widget.ImageButton b=new android.widget.ImageButton(c);b.setImageDrawable(new Icon(name,INK));b.setContentDescription(description);b.setPadding(dp(c,13),dp(c,13),dp(c,13),dp(c,13));b.setBackground(focusable(c,new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33f38bcb),background(CARD,14,c),null),CARD,14));b.setOnClickListener(v->action.run());return b;}
    static final class Icon extends android.graphics.drawable.Drawable {
        private final String name;private final android.graphics.Paint paint=new android.graphics.Paint(3);
        Icon(String name,int color){this.name=name;paint.setColor(color);paint.setStrokeWidth(1.7f);paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);}
        public void draw(android.graphics.Canvas c){c.save();c.translate(getBounds().left,getBounds().top);c.scale(getBounds().width()/24f,getBounds().height()/24f);paint.setStyle(android.graphics.Paint.Style.STROKE);android.graphics.Path p=new android.graphics.Path();
            switch(name){
                case "play":paint.setStyle(android.graphics.Paint.Style.FILL);p.moveTo(8,4);p.lineTo(20,12);p.lineTo(8,20);p.close();c.drawPath(p,paint);break;
                case "pause":paint.setStyle(android.graphics.Paint.Style.FILL);c.drawRoundRect(6,4,10,20,1,1,paint);c.drawRoundRect(14,4,18,20,1,1,paint);break;
                case "plus":c.drawLine(12,5,12,19,paint);c.drawLine(5,12,19,12,paint);break;
                case "check":c.drawLine(5,12,10,17,paint);c.drawLine(10,17,20,6,paint);break;
                case "expand":p.moveTo(9,4);p.lineTo(4,4);p.lineTo(4,9);p.moveTo(15,4);p.lineTo(20,4);p.lineTo(20,9);p.moveTo(4,15);p.lineTo(4,20);p.lineTo(9,20);p.moveTo(20,15);p.lineTo(20,20);p.lineTo(15,20);c.drawPath(p,paint);break;
                case "pip":c.drawRoundRect(2,4,22,20,2,2,paint);paint.setStyle(android.graphics.Paint.Style.FILL);c.drawRoundRect(12,12,19,17,1,1,paint);break;
                case "folder":p.moveTo(3,7);p.lineTo(3,19);p.lineTo(21,19);p.lineTo(21,7);p.lineTo(12,7);p.lineTo(10,4);p.lineTo(3,4);p.close();c.drawPath(p,paint);break;
                case "history":c.drawArc(3,3,21,21,-80,310,false,paint);c.drawLine(3,4,3,10,paint);c.drawLine(3,10,8,10,paint);c.drawLine(12,7,12,12,paint);c.drawLine(12,12,16,14,paint);break;
                case "search":c.drawCircle(10,10,6,paint);c.drawLine(15,15,21,21,paint);break;
                case "back":c.drawLine(15,5,8,12,paint);c.drawLine(8,12,15,19,paint);break;
                case "close":c.drawLine(6,6,18,18,paint);c.drawLine(18,6,6,18,paint);break;
                case "grid":for(int y=4;y<20;y+=10)for(int x=4;x<20;x+=10)c.drawRoundRect(x,y,x+6,y+6,1,1,paint);break;
                case "more":paint.setStyle(android.graphics.Paint.Style.FILL);for(int x=5;x<=19;x+=7)c.drawCircle(x,12,1.5f,paint);break;
                case "menu":for(int y=6;y<=18;y+=6)c.drawLine(4,y,20,y,paint);break;
                case "new":c.drawLine(12,3,12,21,paint);c.drawLine(3,12,21,12,paint);c.drawLine(6,6,18,18,paint);c.drawLine(18,6,6,18,paint);break;
                case "refresh":c.drawArc(4,4,20,20,30,300,false,paint);c.drawLine(21,4,21,10,paint);c.drawLine(21,10,15,10,paint);break;
                default:c.drawLine(4,6,20,6,paint);c.drawCircle(9,6,2,paint);c.drawLine(4,12,20,12,paint);c.drawCircle(16,12,2,paint);c.drawLine(4,18,20,18,paint);c.drawCircle(8,18,2,paint);
            }c.restore();}
        public void setAlpha(int a){paint.setAlpha(a);}public void setColorFilter(android.graphics.ColorFilter f){paint.setColorFilter(f);}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    static void pad(android.view.View v, int n) { int d = dp(v.getContext(), n); v.setPadding(d,d,d,d); }
    static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(java.util.Locale.ROOT,"%d:%02d:%02d",s/3600,s/60%60,s%60)
            : String.format(java.util.Locale.ROOT,"%d:%02d",s/60,s%60);
    }
}
