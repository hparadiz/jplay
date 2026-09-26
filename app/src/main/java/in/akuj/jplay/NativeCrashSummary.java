package in.akuj.jplay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reads an allowlist from Android's tombstone.proto; raw trace is never retained. */
final class NativeCrashSummary {
    static JSONObject read(byte[] bytes) throws Exception {
        Proto root=new Proto(bytes); long crashedTid=-1; byte[] signal=null;
        java.util.ArrayList<byte[]> threads=new java.util.ArrayList<>();
        while(root.next()) {
            if(root.field==6 && root.wire==0)crashedTid=root.number;
            else if(root.field==10 && root.wire==2)signal=root.data();
            else if(root.field==16 && root.wire==2 && threads.size()<128)threads.add(root.data());
        }
        JSONObject result=new JSONObject();
        if(signal!=null) {
            Proto s=new Proto(signal);
            while(s.next()) if(s.wire==0 && (s.field==1||s.field==3)) result.put(s.field==1?"signal":"signalCode",s.number);
        }
        for(byte[] entry:threads) {
            Proto map=new Proto(entry); long tid=-2; byte[] thread=null;
            while(map.next()) { if(map.field==1&&map.wire==0)tid=map.number; else if(map.field==2&&map.wire==2)thread=map.data(); }
            if(tid!=crashedTid||thread==null)continue;
            Proto t=new Proto(thread); JSONArray frames=new JSONArray();
            while(t.next()) if(t.field==4&&t.wire==2&&frames.length()<96)frames.put(frame(t.data()));
            result.put("crashingThreadBacktrace",frames); break;
        }
        result.put("privacy","Only signal and crashing-thread symbols; memory, registers, logs, command lines and paths omitted.");
        return result;
    }
    private static JSONObject frame(byte[] bytes) throws Exception {
        Proto p=new Proto(bytes); JSONObject f=new JSONObject();
        while(p.next()) {
            if(p.wire==0&&(p.field==1||p.field==5)) f.put(p.field==1?"relativePc":"functionOffset",Long.toHexString(p.number));
            else if(p.wire==2&&p.field==4)f.put("symbol",symbol(new String(p.data(),StandardCharsets.UTF_8)));
            else if(p.wire==2&&p.field==6) {
                String path=new String(p.data(),StandardCharsets.UTF_8);
                String name=path.substring(path.lastIndexOf('/')+1);
                f.put("module",name.matches("[A-Za-z0-9_.+-]+\\.(so|apk|oat|art)")?name:"[module]");
            }
        }
        return f;
    }
    private static String symbol(String value) {
        if(value.contains("://"))return "[symbol omitted]";
        return value.substring(0,Math.min(400,value.length())).replaceAll("[^A-Za-z0-9_$:~<>+*(),. &\\[\\]-]","?");
    }
    private static final class Proto {
        final byte[] bytes; int pos,field,wire,start,length; long number;
        Proto(byte[] bytes){this.bytes=bytes;}
        boolean next() throws IOException {
            if(pos==bytes.length)return false;
            long key=varint(); field=(int)(key>>>3);wire=(int)(key&7);
            if(field==0)throw new IOException("Invalid trace field");
            if(wire==0){number=varint();return true;}
            long count=wire==1?8:wire==5?4:wire==2?varint():-1;
            if(count<0||count>bytes.length-pos)throw new IOException("Invalid trace length");
            start=pos;length=(int)count;pos+=length;return true;
        }
        byte[] data(){return Arrays.copyOfRange(bytes,start,start+length);}
        long varint() throws IOException {
            long result=0;
            for(int shift=0;shift<64;shift+=7){if(pos>=bytes.length)break; int b=bytes[pos++]&255;result|=(long)(b&127)<<shift;if((b&128)==0)return result;}
            throw new IOException("Invalid trace integer");
        }
    }
}
