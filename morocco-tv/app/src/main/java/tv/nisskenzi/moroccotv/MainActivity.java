package tv.nisskenzi.moroccotv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
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
  private final int bg=Color.rgb(8,10,15), panel=Color.rgb(24,27,35), red=Color.rgb(229,9,20), green=Color.rgb(0,98,51);
  private LinearLayout root, list; private TextView status, nowTitle; private PlayerView playerView; private ExoPlayer player;
  private final ArrayList<Channel> channels=new ArrayList<>(); private final ExecutorService io=Executors.newSingleThreadExecutor();
  private static class Channel { String name,url,logo,group; Channel(String n,String u,String l,String g){name=n;url=u;logo=l;group=g;} }
  @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); buildHome(); loadChannels();}
  private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
  private android.graphics.drawable.GradientDrawable rounded(int color,int radius){
    android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();
    d.setColor(color);d.setCornerRadius(radius);return d;
  }
  private TextView actionButton(String label,int color){
    TextView b=text(label,13,Color.WHITE,true);b.setGravity(Gravity.CENTER);b.setPadding(18,12,18,12);b.setBackground(rounded(color,10));b.setFocusable(true);return b;
  }
  private void buildHome(){
    root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);root.setPadding(28,18,28,12);setContentView(root);
    LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
    TextView brand=text("N",26,Color.WHITE,true);brand.setGravity(Gravity.CENTER);brand.setBackground(rounded(red,10));header.addView(brand,new LinearLayout.LayoutParams(48,48));
    LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);titles.setPadding(12,0,0,0);
    titles.addView(text("NISSAYSTREAM",22,Color.WHITE,true));titles.addView(text("LE DIRECT, SANS COMPLICATION",10,0xFFADB4C2,true));
    header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
    TextView vavoo=actionButton("VAVOO LIVE",red);vavoo.setOnClickListener(v->{String configured=getSharedPreferences("sources",MODE_PRIVATE).getString("vavoo_server","");if(configured.trim().isEmpty())openVavooSettings();else loadVavooChannels();});vavoo.setOnLongClickListener(v->{openVavooSettings();return true;});header.addView(vavoo);
    TextView refresh=actionButton("↻",panel);LinearLayout.LayoutParams refreshP=new LinearLayout.LayoutParams(48,48);refreshP.leftMargin=8;header.addView(refresh,refreshP);refresh.setOnClickListener(v->loadChannels());root.addView(header);
    LinearLayout hero=new LinearLayout(this);hero.setOrientation(LinearLayout.VERTICAL);hero.setPadding(24,22,24,22);hero.setBackground(rounded(0xFF202633,18));
    LinearLayout.LayoutParams heroP=new LinearLayout.LayoutParams(-1,-2);heroP.topMargin=18;heroP.bottomMargin=10;root.addView(hero,heroP);
    TextView eyebrow=text("NISSAYSTREAM  ORIGINAL",10,0xFFFF5964,true);hero.addView(eyebrow);
    TextView title=text("Le direct.\nEn grand.",30,Color.WHITE,true);LinearLayout.LayoutParams titleP=new LinearLayout.LayoutParams(-1,-2);titleP.topMargin=6;hero.addView(title,titleP);
    TextView desc=text("Retrouve les chaînes marocaines et tes flux en direct dans une interface simple.",13,0xFFD2D7E0,false);LinearLayout.LayoutParams descP=new LinearLayout.LayoutParams(-1,-2);descP.topMargin=6;hero.addView(desc,descP);
    status=text("Chargement des chaînes…",12,0xFFADB4C2,false);LinearLayout.LayoutParams statusP=new LinearLayout.LayoutParams(-1,-2);statusP.topMargin=12;hero.addView(status,statusP);
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(0,8,0,20);scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));list=content;
    TextView foot=text("Flux publics · La disponibilité dépend des diffuseurs",10,0xFF717B8D,false);foot.setGravity(Gravity.CENTER);root.addView(foot);
  }
  private void loadChannels(){status.setText("Mise à jour de la liste…");io.execute(()->{ArrayList<Channel> found=new ArrayList<>();try{
    URL u=new URL("https://iptv-org.github.io/iptv/countries/ma.m3u");HttpURLConnection c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(12000);c.setReadTimeout(18000);c.setRequestProperty("User-Agent","MoroccoTV/1.0");
    BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"));String line,name="Chaîne marocaine",logo="",group="Maroc";
    while((line=r.readLine())!=null){if(line.startsWith("#EXTINF:")){int comma=line.lastIndexOf(',');name=comma>=0?line.substring(comma+1).trim():"Chaîne marocaine";logo="";group="Maroc";int a=line.indexOf("tvg-logo=\"");if(a>=0){int z=line.indexOf('"',a+10);if(z>a)logo=line.substring(a+10,z);}int g=line.indexOf("group-title=\"");if(g>=0){int z=line.indexOf('"',g+13);if(z>g)group=line.substring(g+13,z);}}else if(line.startsWith("http://")||line.startsWith("https://")){found.add(new Channel(name,line.trim(),logo,group));}}
    r.close();c.disconnect();
  }catch(Exception e){}
    // Additional broadcaster streams, used as fallbacks when the community playlist omits them.
    Channel[] extras = new Channel[] {
      new Channel("Al Aoula — الأولى","https://streaming.snrtlive.ma/live/slaalaoula/slaalaoula.m3u8","","SNRT · officiel"),
      new Channel("2M Monde","https://cdnamd-hls-globecast.akamaized.net/live/ramdisk/2m/2m.m3u8","","Diffuseur · flux public"),
      new Channel("Arryadia — الرياضية","https://streaming.snrtlive.ma/live/slaarryadia/slaarryadia.m3u8","","SNRT · officiel"),
      new Channel("Tamazight TV","https://streaming.snrtlive.ma/live/slataamazight/slataamazight.m3u8","","SNRT · officiel"),
      new Channel("Assadissa — السادسة","https://streaming.snrtlive.ma/live/slaassadissa/slaassadissa.m3u8","","SNRT · officiel"),
      new Channel("Al Maghribia — المغربية","https://streaming.snrtlive.ma/live/slamaghribia/slamaghribia.m3u8","","SNRT · officiel"),
      new Channel("Medi1 TV","https://streaming.medi1tv.com/live/smil:medi1tv.smil/playlist.m3u8","","Medi1 · flux public"),
      new Channel("Chada TV","https://chadatv.vedge.infomaniak.com/livecast/chadatv/playlist.m3u8","","Flux public à vérifier")
    };
    for(Channel extra:extras){boolean exists=false;for(Channel current:found){if(current.name.equalsIgnoreCase(extra.name)||current.url.equals(extra.url)){exists=true;break;}}if(!exists)found.add(extra);}
    final ArrayList<Channel> result=found;runOnUiThread(()->showChannels(result));});}
  private void openVavooSettings(){
    SharedPreferences prefs=getSharedPreferences("sources",MODE_PRIVATE);
    String saved=prefs.getString("vavoo_server","");
    EditText input=new EditText(this);input.setSingleLine(true);input.setText(saved);
    input.setHint("http://IP_DU_SERVEUR:8888");
    input.setTextColor(Color.WHITE);input.setHintTextColor(0xFF9BA7BA);
    input.setPadding(18,12,18,12);input.setSelectAllOnFocus(true);
    LinearLayout wrap=new LinearLayout(this);wrap.setPadding(24,8,24,0);wrap.addView(input,new LinearLayout.LayoutParams(-1,-2));
    new AlertDialog.Builder(this).setTitle("VAVOO Live — configuration avancée")
      .setMessage("Cette version nécessite encore un serveur compatible déjà lancé. Aucun achat de proxy n’est nécessaire pour tester les chaînes publiques. Cette configuration n’active pas VAVOO à elle seule.")
      .setView(wrap)
      .setPositiveButton("CONNECTER",(d,w)->{String value=input.getText().toString().trim().replaceAll("/+$","");if(!value.isEmpty()){prefs.edit().putString("vavoo_server",value).apply();loadVavooChannels();}})
      .setNeutralButton("EFFACER",(d,w)->{prefs.edit().remove("vavoo_server").apply();Toast.makeText(this,"Adresse VAVOO effacée",Toast.LENGTH_SHORT).show();})
      .setNegativeButton("ANNULER",null).show();
  }
  private void loadVavooChannels(){
    SharedPreferences prefs=getSharedPreferences("sources",MODE_PRIVATE);
    String server=prefs.getString("vavoo_server","").trim();
    if(server.isEmpty()){openVavooSettings();return;}
    status.setText("Chargement du catalogue VAVOO…");
    io.execute(()->{
      ArrayList<Channel> found=new ArrayList<>();
      String playlist=server.endsWith("/channels.m3u8")?server:server+"/channels.m3u8";
      HttpURLConnection c=null;
      try{
        URL u=new URL(playlist);c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(12000);c.setReadTimeout(25000);c.setRequestProperty("User-Agent","NISSKENZI-TV/1.0");
        BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"));
        String line,name="VAVOO channel",logo="",group="VAVOO Live";
        while((line=r.readLine())!=null){
          if(line.startsWith("#EXTINF:")){
            int comma=line.lastIndexOf(',');name=comma>=0?line.substring(comma+1).trim():"VAVOO channel";logo="";group="VAVOO Live";
            int a=line.indexOf("tvg-logo=\"");
            if(a>=0){int z=line.indexOf('"',a+10);if(z>a)logo=line.substring(a+10,z);}
            int g=line.indexOf("group-title=\"");
            if(g>=0){int z=line.indexOf('"',g+13);if(z>g)group=line.substring(g+13,z);}
          }else if(line.startsWith("http://")||line.startsWith("https://")){
            found.add(new Channel(name,line.trim(),logo,group));
          }
        }
        r.close();
      }catch(Exception e){
        runOnUiThread(()->status.setText("Serveur VAVOO inaccessible. Vérifie l’adresse et le serveur."));
      }finally{if(c!=null)c.disconnect();}
      final ArrayList<Channel> result=found;
      runOnUiThread(()->{if(result.isEmpty())status.setText("Aucune chaîne reçue de VAVOO. Vérifie que /channels.m3u8 répond.");showChannels(result);});
    });
  }
  private void showChannels(ArrayList<Channel> result){
    channels.clear();channels.addAll(result);list.removeAllViews();
    if(channels.isEmpty()){status.setText("Aucun flux reçu. Vérifie la connexion puis actualise.");return;}
    status.setText(channels.size()+" chaînes disponibles · sélectionne une carte pour regarder");
    java.util.LinkedHashMap<String,ArrayList<Channel>> sections=new java.util.LinkedHashMap<>();
    for(Channel ch:channels){String group=(ch.group==null||ch.group.trim().isEmpty())?"En direct":ch.group.trim();if(!sections.containsKey(group))sections.put(group,new ArrayList<>());sections.get(group).add(ch);}
    int count=0;
    for(java.util.Map.Entry<String,ArrayList<Channel>> section:sections.entrySet()){
      LinearLayout sectionBox=new LinearLayout(this);sectionBox.setOrientation(LinearLayout.VERTICAL);
      TextView heading=text(section.getKey().toUpperCase(java.util.Locale.ROOT),14,Color.WHITE,true);
      LinearLayout.LayoutParams headingP=new LinearLayout.LayoutParams(-1,-2);headingP.topMargin=12;headingP.bottomMargin=8;sectionBox.addView(heading,headingP);
      HorizontalScrollView rail=new HorizontalScrollView(this);rail.setHorizontalScrollBarEnabled(false);
      LinearLayout cards=new LinearLayout(this);cards.setOrientation(LinearLayout.HORIZONTAL);
      for(Channel ch:section.getValue()){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(10,10,10,12);card.setBackground(rounded(panel,12));card.setFocusable(true);card.setClickable(true);
        LinearLayout.LayoutParams cardP=new LinearLayout.LayoutParams(154,154);cardP.rightMargin=10;cards.addView(card,cardP);
        TextView poster=text("▶",27,Color.WHITE,true);poster.setGravity(Gravity.CENTER);poster.setBackground(rounded(count%3==0?0xFF9E1018:(count%3==1?0xFF174D3B:0xFF303A50),10));card.addView(poster,new LinearLayout.LayoutParams(-1,82));
        TextView name=text(ch.name,13,Color.WHITE,true);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);LinearLayout.LayoutParams nameP=new LinearLayout.LayoutParams(-1,0,1);nameP.topMargin=8;card.addView(name,nameP);
        TextView meta=text("EN DIRECT",9,0xFFB7C0CF,true);LinearLayout.LayoutParams metaP=new LinearLayout.LayoutParams(-1,-2);metaP.topMargin=4;card.addView(meta,metaP);
        card.setOnClickListener(v->play(ch));card.setOnFocusChangeListener((v,focus)->card.setBackground(rounded(focus?0xFF394253:panel,12)));count++;
      }
      rail.addView(cards);sectionBox.addView(rail);list.addView(sectionBox);
    }
    if(!channels.isEmpty())list.setFocusable(false);
  }
  private void play(Channel ch){
    if(player!=null){player.release();player=null;}root.removeAllViews();root.setPadding(0,0,0,0);root.setOrientation(LinearLayout.VERTICAL);LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(24,12,24,12);top.setBackgroundColor(bg);TextView back=text("‹  CHAÎNES",16,Color.WHITE,true);back.setFocusable(true);back.setPadding(10,10,20,10);back.setOnClickListener(v->{if(player!=null){player.release();player=null;}buildHome();showChannels(new ArrayList<>(channels));});top.addView(back);nowTitle=text(ch.name,19,Color.WHITE,true);nowTitle.setPadding(20,0,0,0);top.addView(nowTitle);root.addView(top,new LinearLayout.LayoutParams(-1,-2));
    playerView=new PlayerView(this);playerView.setUseController(true);root.addView(playerView,new LinearLayout.LayoutParams(-1,0,1));player=new ExoPlayer.Builder(this).build();playerView.setPlayer(player);player.setMediaItem(MediaItem.fromUri(ch.url));player.prepare();player.play();player.addListener(new Player.Listener(){@Override public void onPlayerError(PlaybackException e){Toast.makeText(MainActivity.this,"Flux indisponible ou bloqué par le diffuseur",Toast.LENGTH_LONG).show();}});
  }
  @Override public void onBackPressed(){if(player!=null){player.release();player=null;buildHome();showChannels(new ArrayList<>(channels));}else super.onBackPressed();}
  @Override protected void onDestroy(){if(player!=null)player.release();io.shutdownNow();super.onDestroy();}
}
