package tv.nisskenzi.moroccotv;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import android.text.TextUtils;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
  private final int bg=Color.rgb(12,15,22), panel=Color.rgb(25,30,42), red=Color.rgb(193,39,45), green=Color.rgb(0,98,51);
  private LinearLayout root, list; private TextView status, nowTitle; private PlayerView playerView; private ExoPlayer player;
  private final ArrayList<Channel> channels=new ArrayList<>(); private final ExecutorService io=Executors.newSingleThreadExecutor();
  private static class Channel { String name,url,logo,group; Channel(String n,String u,String l,String g){name=n;url=u;logo=l;group=g;} }
  @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); buildHome(); loadChannels();}
  private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
  private void buildHome(){
    root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);root.setPadding(34,22,34,18);setContentView(root);
    LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setOrientation(LinearLayout.HORIZONTAL);
    TextView flag=text("✦",34,Color.WHITE,true);flag.setBackgroundColor(green);flag.setGravity(Gravity.CENTER);header.addView(flag,new LinearLayout.LayoutParams(54,54));
    LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);titles.setPadding(16,0,0,0);titles.addView(text("MOROCCO  TV",26,Color.WHITE,true));titles.addView(text("LA TÉLÉ MAROCAINE · EN DIRECT",12,0xFFB8C0D0,true));header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
    TextView refresh=text("↻  ACTUALISER",14,Color.WHITE,true);refresh.setPadding(18,14,18,14);refresh.setBackgroundColor(panel);refresh.setFocusable(true);refresh.setOnClickListener(v->loadChannels());header.addView(refresh);root.addView(header);
    TextView heading=text("Les chaînes du Maroc",24,Color.WHITE,true);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=26;hp.bottomMargin=8;root.addView(heading,hp);
    status=text("Chargement des chaînes publiques…",14,0xFFB8C0D0,false);root.addView(status);
    ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(0,10,0,20);scroll.addView(list);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,0,1);sp.topMargin=10;root.addView(scroll,sp);
    TextView foot=text("Sources publiques · La disponibilité dépend des diffuseurs",11,0xFF788397,false);foot.setGravity(Gravity.CENTER);root.addView(foot);
  }
  private void loadChannels(){status.setText("Mise à jour de la liste…");io.execute(()->{ArrayList<Channel> found=new ArrayList<>();try{
    URL u=new URL("https://iptv-org.github.io/iptv/countries/ma.m3u");HttpURLConnection c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(12000);c.setReadTimeout(18000);c.setRequestProperty("User-Agent","MoroccoTV/1.0");
    BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"));String line,name="Chaîne marocaine",logo="",group="Maroc";
    while((line=r.readLine())!=null){if(line.startsWith("#EXTINF:")){int comma=line.lastIndexOf(',');name=comma>=0?line.substring(comma+1).trim():"Chaîne marocaine";logo="";group="Maroc";int a=line.indexOf("tvg-logo=\"");if(a>=0){int z=line.indexOf('"',a+10);if(z>a)logo=line.substring(a+10,z);}int g=line.indexOf("group-title=\"");if(g>=0){int z=line.indexOf('"',g+13);if(z>g)group=line.substring(g+13,z);}}else if(line.startsWith("http://")||line.startsWith("https://")){found.add(new Channel(name,line.trim(),logo,group));}}
    r.close();c.disconnect();
  }catch(Exception e){} final ArrayList<Channel> result=found;runOnUiThread(()->showChannels(result));});}
  private void showChannels(ArrayList<Channel> result){channels.clear();channels.addAll(result);list.removeAllViews();if(channels.isEmpty()){status.setText("Impossible de charger la liste pour le moment. Vérifie la connexion puis actualise.");return;}status.setText(channels.size()+" flux référencés · appuie sur une chaîne pour regarder");
    for(int i=0;i<channels.size();i++){Channel ch=channels.get(i);LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(16,12,16,12);row.setBackgroundColor(panel);row.setFocusable(true);row.setClickable(true);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.bottomMargin=8;list.addView(row,rp);
      TextView badge=text(String.format(java.util.Locale.US,"%02d",i+1),18,Color.WHITE,true);badge.setGravity(Gravity.CENTER);badge.setBackgroundColor(i%3==0?red:green);row.addView(badge,new LinearLayout.LayoutParams(54,54));LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);info.setPadding(16,0,0,0);TextView n=text(ch.name,17,Color.WHITE,true);n.setSingleLine(true);n.setEllipsize(TextUtils.TruncateAt.END);info.addView(n);info.addView(text(ch.group,12,0xFF9BA7BA,false));row.addView(info,new LinearLayout.LayoutParams(0,-2,1));row.addView(text("▶",20,0xFF7DDAA4,true));row.setOnClickListener(v->play(ch));row.setOnFocusChangeListener((v,focus)->v.setBackgroundColor(focus?0xFF374255:panel));if(i==0)row.requestFocus();}
  }
  private void play(Channel ch){
    if(player!=null){player.release();player=null;}root.removeAllViews();root.setPadding(0,0,0,0);root.setOrientation(LinearLayout.VERTICAL);LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(24,12,24,12);top.setBackgroundColor(bg);TextView back=text("‹  CHAÎNES",16,Color.WHITE,true);back.setFocusable(true);back.setPadding(10,10,20,10);back.setOnClickListener(v->{if(player!=null){player.release();player=null;}buildHome();showChannels(new ArrayList<>(channels));});top.addView(back);nowTitle=text(ch.name,19,Color.WHITE,true);nowTitle.setPadding(20,0,0,0);top.addView(nowTitle);root.addView(top,new LinearLayout.LayoutParams(-1,-2));
    playerView=new PlayerView(this);playerView.setUseController(true);root.addView(playerView,new LinearLayout.LayoutParams(-1,0,1));player=new ExoPlayer.Builder(this).build();playerView.setPlayer(player);player.setMediaItem(MediaItem.fromUri(ch.url));player.prepare();player.play();player.addListener(new Player.Listener(){@Override public void onPlayerError(PlaybackException e){Toast.makeText(MainActivity.this,"Flux indisponible ou bloqué par le diffuseur",Toast.LENGTH_LONG).show();}});
  }
  @Override public void onBackPressed(){if(player!=null){player.release();player=null;buildHome();showChannels(new ArrayList<>(channels));}else super.onBackPressed();}
  @Override protected void onDestroy(){if(player!=null)player.release();io.shutdownNow();super.onDestroy();}
}
