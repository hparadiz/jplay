package in.akuj.jplay;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import javax.net.SocketFactory;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

/** Small, bounded SMB writes; the video path continues to use LibVLC. */
final class NasUploader {
    static void upload(Profile p, String name, byte[] bytes) throws IOException {
        if (!name.matches("[A-Za-z0-9-]+\\.json") || bytes.length > 128 * 1024)
            throw new IOException("Invalid report");
        SmbConfig config = SmbConfig.builder().withTimeout(10,TimeUnit.SECONDS)
            .withSoTimeout(10,TimeUnit.SECONDS).withSocketFactory(new BoundedSockets()).withDfsEnabled(false).build();
        char[] password = p.password.toCharArray();
        try (SMBClient client = new SMBClient(config);
             Connection connection = client.connect(p.root().getHost(),p.root().getPort()>0?p.root().getPort():445);
             Session session = connection.authenticate(p.user.isEmpty() ? AuthenticationContext.anonymous()
                 : new AuthenticationContext(p.user,password,p.domain));
             DiskShare share = (DiskShare)session.connectShare(p.share)) {
            if (!share.folderExists("JPlay")) share.mkdir("JPlay");
            if (!share.folderExists("JPlay\\Crashes")) share.mkdir("JPlay\\Crashes");
            // Stable name makes a retry idempotent if the acknowledgement was lost.
            // Rename exposes the report only after the entire write has flushed.
            String destination="JPlay\\Crashes\\"+name;
            try (com.hierynomus.smbj.share.File file = share.openFile(destination+".part",
                    EnumSet.of(AccessMask.GENERIC_WRITE,AccessMask.DELETE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))) {
                if (file.write(bytes,0)!=bytes.length) throw new IOException("Incomplete report write");
                file.flush(); file.rename(destination,true);
            }
            // Read back a small report before removing the durable local copy.
            try (com.hierynomus.smbj.share.File file = share.openFile(destination,
                    EnumSet.of(AccessMask.GENERIC_READ),EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))) {
                byte[] check = new byte[bytes.length]; int offset=0;
                while (offset<check.length) {
                    int count=file.read(check,offset,offset,check.length-offset);
                    if(count<=0)throw new IOException("Incomplete verification read"); offset+=count;
                }
                if(!Arrays.equals(bytes,check))throw new IOException("Report verification failed");
            }
        } finally { Arrays.fill(password,'\0'); }
    }
    static final class BoundedSockets extends SocketFactory {
        private Socket connect(InetSocketAddress address,InetAddress local,int port) throws IOException {
            Socket socket=new Socket();
            try {if(local!=null)socket.bind(new InetSocketAddress(local,port));socket.connect(address,10000);socket.setSoTimeout(10000);return socket;}
            catch(IOException e){socket.close();throw e;}
        }
        @Override public Socket createSocket(){return new Socket();}
        @Override public Socket createSocket(String host,int port) throws IOException{return connect(new InetSocketAddress(host,port),null,0);}
        @Override public Socket createSocket(InetAddress host,int port) throws IOException{return connect(new InetSocketAddress(host,port),null,0);}
        @Override public Socket createSocket(String host,int port,InetAddress local,int localPort) throws IOException{return connect(new InetSocketAddress(host,port),local,localPort);}
        @Override public Socket createSocket(InetAddress host,int port,InetAddress local,int localPort) throws IOException{return connect(new InetSocketAddress(host,port),local,localPort);}
    }
}
