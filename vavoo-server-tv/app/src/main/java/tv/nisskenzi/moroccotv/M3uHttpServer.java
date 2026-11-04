package tv.nisskenzi.moroccotv;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

final class M3uHttpServer {
    private static final class Channel {
        final String meta,url;
        Channel(String m,String u){meta=m;url=u;}
    }
    private final Context context;
    private ServerSocket socket;
    private volatile boolean serving;
    private final ExecutorService workers=Executors.newFixedThreadPool(6);
    private final Map<Integer,List<Channel>> cache=new HashMap<>();
    private final Map<Integer,Long> timestamp=new HashMap<>();
    M3uHttpServer(Context c){context=c.getApplicationContext();}
    synchronized void start() throws IOException{
        if(serving)return;
        socket=new ServerSocket(ServerTvActivity.PORT,32,InetAddress.getByName("0.0.0.0"));
        serving=true;
        new Thread(()->{
            while(serving){
                try{
                    Socket c=socket.accept();
                    workers.execute(()->serve(c));
                }catch(Exception e){if(serving)android.util.Log.w("VavooServer","Accept",e);}
            }
        },"m3u-http-accept").start();
    }
    synchronized void stop(){
        serving=false;
        try{if(socket!=null)socket.close();}catch(Exception ignored){}
        workers.shutdownNow();
    }
    private static String read(InputStream in,int limit) throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] b=new byte[8192];int n;
        while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("Liste trop volumineuse");out.write(b,0,n);}
        return out.toString("UTF-8");
    }
    private synchronized List<Channel> channels(int source) throws Exception {
        long now=System.currentTimeMillis();
        if(cache.containsKey(source)&&now-timestamp.get(source)<1200000)return cache.get(source);
        String address=ServerTvActivity.sourceUrl(context,source);
        if(!address.startsWith("https://")&&!address.startsWith("http://"))throw new IOException("URL M3U manquante");
        HttpURLConnection conn=(HttpURLConnection)new URL(address).openConnection();
        conn.setConnectTimeout(12000);conn.setReadTimeout(25000);
        conn.setRequestProperty("User-Agent","VavooServerTV/1.0");
        String m3u;
        try{
            if(conn.getResponseCode()!=200)throw new IOException("M3U HTTP "+conn.getResponseCode());
            try(InputStream input=conn.getInputStream()){m3u=read(input,32000000);}
        }finally{conn.disconnect();}
        if(!m3u.startsWith("#EXTM3U"))throw new IOException("Ce fichier n'est pas une M3U");
        List<Channel> parsed=new ArrayList<>();
        String meta=null;
        try(BufferedReader r=new BufferedReader(new StringReader(m3u))){
            String line;
            while((line=r.readLine())!=null){
                line=line.trim();
                if(line.startsWith("#EXTINF:"))meta=line;
                else if(meta!=null&&!line.startsWith("#")&&!line.isEmpty()){
                    if(line.startsWith("vavoo://")||line.startsWith("http://")||line.startsWith("https://"))
                        parsed.add(new Channel(meta,line));
                    meta=null;
                }
            }
        }
        if(parsed.isEmpty())throw new IOException("Liste sans chaîne");
        cache.put(source,parsed);timestamp.put(source,now);
        return parsed;
    }
    private void serve(Socket conn){
        try(Socket socket=conn){
            socket.setSoTimeout(45000);
            BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            String request=in.readLine();
            if(request==null||request.length()>4096)return;
            String[] fields=request.split(" ");
            if(fields.length<2||!("GET".equals(fields[0])||"HEAD".equals(fields[0]))){
                respond(socket,false,405,"text/plain","GET requis",null);return;
            }
            boolean head="HEAD".equals(fields[0]);
            String path=fields[1].split("\\?")[0];
            if(path.equals("/health")||path.equals("/")){
                respond(socket,head,200,"text/plain","VAVOO Server TV OK",null);return;
            }
            int source=ServerTvActivity.source(context);
            if(path.matches("/list/[0-4]\\.m3u"))source=Integer.parseInt(path.substring(6,7));
            if(path.equals("/playlist.m3u")||path.matches("/list/[0-4]\\.m3u")){
                try{
                    List<Channel> arr=channels(source);
                    StringBuilder body=new StringBuilder("#EXTM3U\n");
                    String base="http://"+ServerTvActivity.ip()+":"+ServerTvActivity.PORT;
                    for(int i=0;i<arr.size();i++)body.append(arr.get(i).meta).append('\n')
                        .append(base).append("/play/").append(source).append('/').append(i).append('\n');
                    respond(socket,head,200,"audio/x-mpegurl",body.toString(),null);
                }catch(Exception error){respond(socket,head,502,"text/plain","M3U indisponible : "+error.getMessage(),null);}
                return;
            }
            if(path.matches("/play/[0-4]/[0-9]{1,8}")){
                String[] p=path.split("/");
                try{
                    int s=Integer.parseInt(p[2]);int i=Integer.parseInt(p[3]);
                    List<Channel> arr=channels(s);
                    if(i<0||i>=arr.size()){respond(socket,head,404,"text/plain","Chaîne absente",null);return;}
                    String target=arr.get(i).url;
                    if(target.startsWith("vavoo://")){
                        String id=target.substring(8);
                        if(!id.matches("[a-zA-Z0-9_-]{3,128}"))throw new IOException("ID VAVOO invalide");
                        target=VavooProvider.resolveStream("https://vavoo.to/vavoo-iptv/play/"+id);
                    }
                    if(!target.startsWith("http://")&&!target.startsWith("https://"))throw new IOException("Flux invalide");
                    respond(socket,head,302,"text/plain","Redirection",target);
                }catch(Exception e){respond(socket,head,502,"text/plain","Flux indisponible : "+e.getMessage(),null);}
                return;
            }
            respond(socket,head,404,"text/plain","Introuvable",null);
        }catch(Exception e){android.util.Log.w("VavooServer","Client",e);}
    }
    private static void respond(Socket socket,boolean head,int code,String mime,String body,String location)throws IOException{
        byte[] payload=body.getBytes(StandardCharsets.UTF_8);
        String reason=code==200?"OK":code==302?"Found":code==404?"Not Found":code==405?"Method Not Allowed":"Bad Gateway";
        StringBuilder h=new StringBuilder("HTTP/1.1 "+code+" "+reason+"\r\n")
            .append("Content-Type: ").append(mime).append("; charset=utf-8\r\n")
            .append("Content-Length: ").append(payload.length).append("\r\n")
            .append("Cache-Control: no-store\r\nConnection: close\r\n");
        if(location!=null)h.append("Location: ").append(location).append("\r\n");
        h.append("\r\n");
        OutputStream out=socket.getOutputStream();out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        if(!head)out.write(payload);out.flush();
    }
}
