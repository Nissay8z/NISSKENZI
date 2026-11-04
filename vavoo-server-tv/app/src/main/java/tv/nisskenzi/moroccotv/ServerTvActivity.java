package tv.nisskenzi.moroccotv;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.net.*;
import java.util.*;

public class ServerTvActivity extends Activity {
    static final int PORT = 8787;
    static final String[] NAMES = {"VAVOO.to", "VAVOO.net", "VAVOO.top", "KOOL.to", "Ma M3U"};
    static final String[] URLS = {
        "https://raw.githubusercontent.com/Nissay8z/KENZINISS/main/playlist/vavoo-to.m3u",
        "https://raw.githubusercontent.com/Nissay8z/KENZINISS/main/playlist/vavoo-net.m3u",
        "https://raw.githubusercontent.com/Nissay8z/KENZINISS/main/playlist/vavoo-top.m3u",
        "https://raw.githubusercontent.com/Nissay8z/KENZINISS/main/playlist/kool-to.m3u"
    };
    private TextView status, link, selection;
    private EditText custom;
    static android.content.SharedPreferences pref(Context c) { return c.getSharedPreferences("vavoo", MODE_PRIVATE); }
    static int source(Context c) { int i=pref(c).getInt("source",0); return i>=0&&i<5?i:0; }
    static String sourceUrl(Context c,int i) { return i==4?pref(c).getString("custom",""):URLS[i]; }
    static String ip() {
        try {
            Enumeration<NetworkInterface> list=NetworkInterface.getNetworkInterfaces();
            while (list.hasMoreElements()) {
                NetworkInterface n=list.nextElement();
                if(!n.isUp()||n.isLoopback()||n.isVirtual())continue;
                Enumeration<InetAddress> add=n.getInetAddresses();
                while(add.hasMoreElements()){
                    InetAddress a=add.nextElement();
                    if(a instanceof Inet4Address&&!a.isLoopbackAddress()&&a.isSiteLocalAddress())return a.getHostAddress();
                }
            }
        }catch(Exception ignored){}
        return "127.0.0.1";
    }
    static String playlist(){return "http://"+ip()+":"+PORT+"/playlist.m3u";}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable bg(int color){
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(10)); return d;
    }
    private TextView text(String label,int size,int color){
        TextView t=new TextView(this);t.setText(label);t.setTextSize(size);t.setTextColor(color);
        t.setPadding(dp(6),dp(7),dp(6),dp(7));return t;
    }
    private Button btn(String s){
        Button b=new Button(this);b.setText(s);b.setTextSize(17);b.setAllCaps(false);b.setTextColor(Color.WHITE);
        b.setBackground(bg(0xff243b59));b.setFocusable(true);
        b.setOnFocusChangeListener((v,on)->b.setBackground(bg(on?0xff217ed3:0xff243b59)));
        return b;
    }
    private void update(){
        if(selection!=null) selection.setText("Source active : "+NAMES[source(this)]);
        if(link!=null) link.setText(playlist());
        if(status!=null) status.setText(ServerTvService.active?"● SERVEUR ACTIF":"○ SERVEUR EN COURS DE DÉMARRAGE");
    }
    private void launch(){
        Intent intent=new Intent(this,ServerTvService.class);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
    }
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xff0d1525);
        LinearLayout root=new LinearLayout(this);root.setPadding(dp(35),dp(20),dp(35),dp(25));root.setOrientation(1);
        scroll.addView(root);setContentView(scroll);
        root.addView(text("NISSKENZI • ANDROID TV",17,0xff57b5ff));
        root.addView(text("VAVOO Server TV",35,Color.WHITE));
        root.addView(text("Ta box transforme les playlists VAVOO en M3U accessibles sur ton Wi-Fi.",19,0xffb9c9dc));
        selection=text("",21,Color.WHITE);root.addView(selection);
        LinearLayout row=new LinearLayout(this);row.setOrientation(0);root.addView(row);
        for(int i=0;i<4;i++){
            final int idx=i;Button button=btn(NAMES[i]);
            row.addView(button,new LinearLayout.LayoutParams(0,dp(60),1f));
            button.setOnClickListener(v->{pref(this).edit().putInt("source",idx).apply();update();});
            if(i==0)button.requestFocus();
        }
        custom=new EditText(this);
        custom.setSingleLine(true);custom.setTextSize(18);custom.setTextColor(Color.WHITE);
        custom.setHintTextColor(0xff9bafc8);custom.setHint("Coller ici une autre URL M3U (https://...)");
        custom.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        custom.setText(pref(this).getString("custom",""));
        root.addView(custom);
        Button save=btn("Enregistrer ma M3U");root.addView(save);
        save.setOnClickListener(v->{
            String u=custom.getText().toString().trim();
            if(!u.startsWith("https://")&&!u.startsWith("http://")){
                Toast.makeText(this,"Adresse M3U HTTP(S) requise",Toast.LENGTH_LONG).show();return;
            }
            pref(this).edit().putString("custom",u).putInt("source",4).apply();update();
            Toast.makeText(this,"M3U enregistrée",Toast.LENGTH_SHORT).show();
        });
        status=text("",20,0xff76dcaa);root.addView(status);
        root.addView(text("ADRESSE À METTRE DANS TON LECTEUR IPTV :",16,0xffb9c9dc));
        link=text("",24,Color.WHITE);link.setTextIsSelectable(true);root.addView(link);
        Button copy=btn("COPIER LE LIEN");root.addView(copy);
        copy.setOnClickListener(v->{
            ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(cm!=null)cm.setPrimaryClip(ClipData.newPlainText("VAVOO M3U",playlist()));
            Toast.makeText(this,"Lien copié",Toast.LENGTH_SHORT).show();
        });
        Button stop=btn("ARRÊTER LE SERVEUR");root.addView(stop);
        stop.setOnClickListener(v->{stopService(new Intent(this,ServerTvService.class));update();});
        Button start=btn("DÉMARRER LE SERVEUR");root.addView(start);
        start.setOnClickListener(v->{launch();update();});
        root.addView(text("L'appli doit rester active pour que les autres lecteurs puissent utiliser l'adresse. "
          +"Les flux VAVOO sont résolus à la lecture ; leur disponibilité n'est pas garantie.",15,0xff9eafc4));
        launch();update();
        root.postDelayed(this::update,850);
    }
    @Override public void onResume(){super.onResume();if(status!=null)update();}
}
