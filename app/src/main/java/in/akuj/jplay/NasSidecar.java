package in.akuj.jplay;

import android.net.Uri;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.mssmb2.*;
import com.hierynomus.smbj.*;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Writes only Just Play's sidecar directory inside an existing library folder. */
final class NasSidecar implements AutoCloseable {
    private SMBClient client;
    private Connection connection;private Session session;private DiskShare share;
    private final String base,folderPath;
    /** Connects to the share alone, for reading the library tree rather than a sidecar. */
    NasSidecar(Profile profile) throws Exception {
        folderPath="";base="";connect(profile);
    }
    NasSidecar(Profile profile,Uri folder) throws Exception {
        if(!profile.contains(folder)||folder.getPathSegments().size()<2)throw new IOException("Invalid show folder");
        List<String> parts=folder.getPathSegments();StringBuilder path=new StringBuilder();
        for(int i=1;i<parts.size();i++){
            String part=parts.get(i);
            if(part.isEmpty()||part.equals(".")||part.equals("..")||part.matches(".*[\\\\/:*?\"<>|\u0000].*"))throw new IOException("Invalid folder name");
            if(path.length()>0)path.append('\\');path.append(part);
        }
        folderPath=path.toString();base=path+"\\.justplay";
        connect(profile);
        if(!share.folderExists(folderPath)){try{close();}catch(Exception ignored){}throw new IOException("Show folder missing");}
    }
    private void connect(Profile profile) throws Exception {
        client=new SMBClient(SmbConfig.builder().withTimeout(10,TimeUnit.SECONDS).withSoTimeout(10,TimeUnit.SECONDS)
            .withSocketFactory(new NasUploader.BoundedSockets()).withDfsEnabled(false).build());
        char[] secret=profile.password.toCharArray();
        try {
            connection=client.connect(profile.root().getHost(),profile.root().getPort()>0?profile.root().getPort():445);
            session=connection.authenticate(profile.user.isEmpty()?AuthenticationContext.anonymous():new AuthenticationContext(profile.user,secret,profile.domain));
            share=(DiskShare)session.connectShare(profile.share);
        } catch(Exception e){close();throw e;} finally{Arrays.fill(secret,'\0');}
    }
    /**
     * A folder's own timestamp moves when a file is added to it or removed from it, and a
     * listing hands back every child's timestamp for free. So the walk reads one folder,
     * compares each child folder's stamp with the one recorded last time, and descends only
     * into the ones that moved. An unchanged library costs a single listing per top folder;
     * a new episode costs the path down to it and nothing else.
     */
    /**
     * When this entry landed here. A file copied onto the NAS keeps the release's own write
     * time, so creation time is the honest answer for a file; a folder has no meaningful
     * creation time but its write time moves whenever a child lands, which is what the walk
     * prunes on.
     */
    static long landed(com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation info){
        long written=info.getLastWriteTime()==null?0:info.getLastWriteTime().toEpochMillis();
        if((info.getFileAttributes()&FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue())!=0)return written;
        long created=info.getCreationTime()==null?0:info.getCreationTime().toEpochMillis();
        return created>0?created:written;
    }
    interface Tree {
        /** Whether this folder's children still have to be read. */
        boolean stale(String path,long modified);
        void file(String path,long modified);
        /** Called once a folder's children have been read at this stamp. */
        void scanned(String path,long modified);
    }
    void walk(String path,int depth,int limit,Tree tree){
        if(depth<0||tree==null)return;
        List<com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation> children;
        try{children=share.list(path);}catch(RuntimeException e){return;}
        int seen=0;
        for(com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation info:children){
            if(++seen>limit)return;
            String name=info.getFileName();
            if(name==null||name.isEmpty()||name.startsWith(".")||".".equals(name)||"..".equals(name))continue;
            String child=path.isEmpty()?name:path+"\\"+name;
            long stamp=landed(info);
            if((info.getFileAttributes()&FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue())!=0){
                if(depth>0&&tree.stale(child,stamp)){walk(child,depth-1,limit,tree);tree.scanned(child,stamp);}
                continue;
            }
            tree.file(child,stamp);
        }
    }
    String path(Uri folder){
        List<String> parts=folder.getPathSegments();StringBuilder path=new StringBuilder();
        for(int i=1;i<parts.size();i++){if(path.length()>0)path.append('\\');path.append(parts.get(i));}
        return path.toString();
    }
    /**
     * When each child of this folder last changed on the NAS. SMB knows what landed and when;
     * the network listing the library browses with does not carry it.
     */
    HashMap<String,Long> modified(){
        HashMap<String,Long> times=new HashMap<>();
        for(com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation info:share.list(folderPath)){
            String name=info.getFileName();
            if(name==null||".".equals(name)||"..".equals(name))continue;
            times.put(name,landed(info));
        }
        return times;
    }
    private String path(String name) throws IOException {
        if(base.isEmpty())throw new IOException("Not scoped to a show folder");
        if(!name.matches("(?:show\\.json|(?:poster|episode)-[a-f0-9]{64}\\.jpg)"))throw new IOException("Invalid sidecar name");
        return base+"\\"+name;
    }
    byte[] read(String name,int limit) throws IOException {
        String path=path(name);if(!share.folderExists(base)||!share.fileExists(path))return null;
        try(com.hierynomus.smbj.share.File file=share.openFile(path,EnumSet.of(AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),SMB2ShareAccess.ALL,SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));InputStream in=file.getInputStream()){
            return ShowMetadata.readBounded(in,limit);
        }
    }
    void write(String name,byte[] bytes,int limit) throws IOException {
        if(bytes.length>limit)throw new IOException("Sidecar too large");
        String destination=path(name),temporary=destination+"."+UUID.randomUUID()+".part";
        if(!share.folderExists(base))try{share.mkdir(base);}catch(RuntimeException e){if(!share.folderExists(base))throw e;}
        try {
            try(com.hierynomus.smbj.share.File file=share.openFile(temporary,EnumSet.of(AccessMask.GENERIC_WRITE,AccessMask.DELETE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_CREATE,EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))){
                int offset=0;while(offset<bytes.length){long n=file.write(bytes,offset,offset,bytes.length-offset);if(n<=0)throw new IOException("Incomplete sidecar write");offset+=(int)n;}
                file.flush();file.rename(destination,true);
            }
            if(!Arrays.equals(bytes,read(name,limit)))throw new IOException("Sidecar verification failed");
        } finally {try{if(share.fileExists(temporary))share.rm(temporary);}catch(Exception ignored){}}
    }
    @Override public void close() throws Exception {
        try{if(share!=null)share.close();}finally{try{if(session!=null)session.close();}finally{try{if(connection!=null)connection.close();}finally{client.close();}}}
    }
}
