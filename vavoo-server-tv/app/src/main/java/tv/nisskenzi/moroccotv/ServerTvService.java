package tv.nisskenzi.moroccotv;
import android.app.*;
import android.content.*;
import android.os.*;

public class ServerTvService extends Service {
    static volatile boolean active=false;
    private M3uHttpServer server;
    @Override public void onCreate(){super.onCreate();server=new M3uHttpServer(this);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        final String channel="vavoo_server";
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel nc=new NotificationChannel(channel,"VAVOO Server TV",NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm=getSystemService(NotificationManager.class);if(nm!=null)nm.createNotificationChannel(nc);
        }
        Intent launch=new Intent(this,ServerTvActivity.class);
        int f=PendingIntent.FLAG_UPDATE_CURRENT;
        if(Build.VERSION.SDK_INT>=23)f|=PendingIntent.FLAG_IMMUTABLE;
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,channel):new Notification.Builder(this);
        Notification notification=builder.setContentTitle("VAVOO Server TV")
            .setContentText("Serveur M3U disponible sur le port 8787")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(PendingIntent.getActivity(this,0,launch,f)).setOngoing(true).build();
        startForeground(8787,notification);
        if(!active){
            try{server.start();active=true;}catch(Exception error){
                android.util.Log.e("VavooServer","Démarrage impossible",error);active=false;stopSelf();
                return START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }
    @Override public void onDestroy(){if(server!=null)server.stop();active=false;super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
