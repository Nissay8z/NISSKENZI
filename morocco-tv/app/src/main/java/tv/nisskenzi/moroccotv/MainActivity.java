package tv.nisskenzi.moroccotv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
  private LinearLayout root, list; private TextView status, nowTitle; private EditText searchBox; private PlayerView playerView; private ExoPlayer player; private Channel currentChannel; private String activeFilter="TOUT"; private String searchQuery="";
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
    root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);root.setPadding(20,14,20,10);setContentView(root);
    LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
    TextView brand=text("N",27,Color.WHITE,true);brand.setGravity(Gravity.CENTER);brand.setBackground(rounded(red,14));header.addView(brand,new LinearLayout.LayoutParams(44,44));
    LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);titles.setPadding(12,0,0,0);
    titles.addView(text("NISSAYSTREAM",20,Color.WHITE,true));titles.addView(text("TON UNIVERS EN DIRECT",9,0xFFADB4C2,true));
    header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
    TextView importBtn=actionButton("＋",panel);importBtn.setOnClickListener(v->openSourceMenu());header.addView(importBtn); TextView vavoo=actionButton("VAVOO",red);vavoo.setOnClickListener(v->{String configured=getSharedPreferences("sources",MODE_PRIVATE).getString("vavoo_server","");if(configured.trim().isEmpty())openVavooSettings();else loadVavooChannels();});vavoo.setOnLongClickListener(v->{openVavooSettings();return true;});header.addView(vavoo);
    TextView refresh=actionButton("↻",panel);LinearLayout.LayoutParams refreshP=new LinearLayout.LayoutParams(48,48);refreshP.leftMargin=8;header.addView(refresh,refreshP);refresh.setOnClickListener(v->loadChannels());root.addView(header);
    searchBox=new EditText(this);searchBox.setSingleLine(true);searchBox.setTextSize(14);searchBox.setTextColor(Color.WHITE);searchBox.setHintTextColor(0xFF8993A4);searchBox.setHint("⌕   Rechercher une chaîne…");searchBox.setPadding(18,4,18,4);searchBox.setBackground(rounded(0xFF171B24,14));searchBox.setText(searchQuery);LinearLayout.LayoutParams searchP=new LinearLayout.LayoutParams(-1,50);searchP.topMargin=16;root.addView(searchBox,searchP);searchBox.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){searchQuery=s.toString();showChannels(null);}public void afterTextChanged(android.text.Editable e){}});
    LinearLayout hero=new LinearLayout(this);hero.setOrientation(LinearLayout.VERTICAL);hero.setPadding(22,20,22,20);android.graphics.drawable.GradientDrawable heroBg=new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,new int[]{0xFF31394B,0xFF171B25,0xFF40161D});heroBg.setCornerRadius(22);hero.setBackground(heroBg);
    LinearLayout.LayoutParams heroP=new LinearLayout.LayoutParams(-1,-2);heroP.topMargin=18;heroP.bottomMargin=10;root.addView(hero,heroP);
    TextView eyebrow=text("À LA UNE  •  DIRECT",10,0xFFFF5964,true);hero.addView(eyebrow);
    TextView title=text("Le direct.\nEn grand.",30,Color.WHITE,true);LinearLayout.LayoutParams titleP=new LinearLayout.LayoutParams(-1,-2);titleP.topMargin=6;hero.addView(title,titleP);
    TextView desc=text("Tes chaînes et tes playlists, réunies au même endroit.",13,0xFFD2D7E0,false);LinearLayout.LayoutParams descP=new LinearLayout.LayoutParams(-1,-2);descP.topMargin=6;hero.addView(desc,descP);
    status=text("Chargement des chaînes…",12,0xFFADB4C2,false);LinearLayout.LayoutParams statusP=new LinearLayout.LayoutParams(-1,-2);statusP.topMargin=12;hero.addView(status,statusP);
    LinearLayout filters=new LinearLayout(this);filters.setOrientation(LinearLayout.HORIZONTAL);filters.setPadding(0,6,0,4);for(String f:new String[]{"TOUT","FAVORIS"}){TextView chip=actionButton(f,f.equals(activeFilter)?red:panel);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);cp.rightMargin=8;filters.addView(chip,cp);chip.setOnClickListener(v->{activeFilter=f;showChannels(null);});}root.addView(filters);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(0,8,0,20);scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));list=content;
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
  private void openSourceMenu(){
    new AlertDialog.Builder(this).setTitle("Ajouter une source").setItems(new String[]{"Playlist M3U/M3U8","Xtream Codes","VXParser VAVOO","Portail MAC / Stalker"},(d,which)->{if(which==0)openPlaylistImport();else if(which==1)openXtream();else if(which==2)openVxParser();else openMacPortal();}).setNegativeButton("Annuler",null).show();
  }
  private EditText sourceField(String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);e.setTextColor(Color.WHITE);e.setHintTextColor(0xFF9BA7BA);return e;}
  private void openXtream(){
    LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(24,4,24,0);
    SharedPreferences p=getSharedPreferences("sources",MODE_PRIVATE);
    EditText host=sourceField("Serveur, ex. https://exemple.com:8080"), user=sourceField("Nom d’utilisateur"), pass=sourceField("Mot de passe");
    host.setText(p.getString("xc_host",""));user.setText(p.getString("xc_user",""));pass.setText(p.getString("xc_pass",""));
    box.addView(host);box.addView(user);box.addView(pass);
    new AlertDialog.Builder(this).setTitle("Xtream Codes").setMessage("Entre les identifiants fournis par ton service IPTV.").setView(box).setPositiveButton("CONNECTER",(d,w)->{
      String h=host.getText().toString().trim().replaceAll("/+$",""),u=user.getText().toString().trim(),pw=pass.getText().toString().trim();
      if(h.isEmpty()||u.isEmpty()||pw.isEmpty()){Toast.makeText(this,"Les trois champs sont obligatoires",Toast.LENGTH_SHORT).show();return;}
      p.edit().putString("xc_host",h).putString("xc_user",u).putString("xc_pass",pw).apply();
      loadImportedPlaylist(h+"/get.php?username="+android.net.Uri.encode(u)+"&password="+android.net.Uri.encode(pw)+"&type=m3u_plus&output=ts");
    }).setNegativeButton("Annuler",null).show();
  }
  private void openVxParser(){
    SharedPreferences p=getSharedPreferences("sources",MODE_PRIVATE);
    EditText host=sourceField("Adresse VXParser, ex. http://127.0.0.1:8080");
    host.setText(p.getString("vxparser_host",""));
    LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(24,4,24,0);box.addView(host);
    new AlertDialog.Builder(this).setTitle("VXParser · VAVOO + Xtream")
      .setMessage("VXParser est un serveur Python/FastAPI séparé, pas une bibliothèque Android intégrée. Démarre-le sur ton téléphone (par exemple avec Termux) ou sur un appareil de ton réseau, puis colle son adresse ici. Il faut avoir généré la liste LiveTV dans VXParser au moins une fois. L’application récupère ensuite sa playlist Xtream/M3U; elle ne lance pas VXParser toute seule.")
      .setView(box).setPositiveButton("CONNECTER",(d,w)->{
        String h=host.getText().toString().trim().replaceAll("/+$","");
        if(h.isEmpty()){Toast.makeText(this,"Adresse du serveur obligatoire",Toast.LENGTH_SHORT).show();return;}
        p.edit().putString("vxparser_host",h).apply();
        loadImportedPlaylist(h+"/get.php?username=nissay&password=stream&type=m3u_plus&output=hls");
      }).setNegativeButton("Annuler",null).show();
  }
  private void openMacPortal(){
    LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(24,4,24,0);
    EditText portal=sourceField("URL du portail Stalker"),mac=sourceField("Adresse MAC de l’appareil autorisé");
    box.addView(portal);box.addView(mac);
    new AlertDialog.Builder(this).setTitle("Portail MAC / Stalker").setMessage("Les portails Stalker n’ont pas tous le même protocole. Cette option teste uniquement les portails qui exposent directement une playlist M3U via /playlist.m3u?mac=…; elle ne garantit pas la compatibilité avec tous les portails.").setView(box).setPositiveButton("TESTER",(d,w)->{
      String h=portal.getText().toString().trim().replaceAll("/+$",""),m=mac.getText().toString().trim();
      if(h.isEmpty()||m.isEmpty()){Toast.makeText(this,"URL et adresse MAC obligatoires",Toast.LENGTH_SHORT).show();return;}
      getSharedPreferences("sources",MODE_PRIVATE).edit().putString("mac_portal",h).putString("mac_address",m).apply();
      loadImportedPlaylist(h+"/playlist.m3u?mac="+android.net.Uri.encode(m));
    }).setNegativeButton("Annuler",null).show();
  }
  private void openPlaylistImport(){
    EditText input=new EditText(this);input.setSingleLine(true);input.setHint("URL complète de la playlist .m3u/.m3u8");input.setTextColor(Color.WHITE);input.setHintTextColor(0xFF9BA7BA);
    String saved=getSharedPreferences("sources",MODE_PRIVATE).getString("m3u_url","");input.setText(saved);
    LinearLayout box=new LinearLayout(this);box.setPadding(24,8,24,0);box.addView(input,new LinearLayout.LayoutParams(-1,-2));
    new AlertDialog.Builder(this).setTitle("Importer une playlist").setMessage("Colle une playlist M3U/M3U8 accessible et que tu es autorisé à utiliser.").setView(box).setPositiveButton("CHARGER",(d,w)->{String u=input.getText().toString().trim();if(!u.isEmpty()){getSharedPreferences("sources",MODE_PRIVATE).edit().putString("m3u_url",u).apply();loadImportedPlaylist(u);}}).setNegativeButton("Annuler",null).show();
  }
  private void loadImportedPlaylist(String playlist){
    status.setText("Chargement de la playlist…");
    io.execute(()->{
      ArrayList<Channel> found=new ArrayList<>();String error="";
      try{
        HttpURLConnection c=(HttpURLConnection)new URL(playlist).openConnection();c.setConnectTimeout(12000);c.setReadTimeout(20000);c.setRequestProperty("User-Agent","NissayStream/1.3");
        int code=c.getResponseCode();if(code<200||code>=300)throw new java.io.IOException("HTTP "+code);
        BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"));String line,name="Chaîne importée",logo="",group="Playlist importée";
        while((line=r.readLine())!=null){line=line.trim();if(line.startsWith("#EXTINF:")){int comma=line.lastIndexOf(',');name=comma>=0?line.substring(comma+1).trim():"Chaîne importée";logo="";group="Playlist importée";logo=attribute(line,"tvg-logo");group=attribute(line,"group-title");}else if((line.startsWith("http://")||line.startsWith("https://"))&&!line.startsWith("#"))found.add(new Channel(name,line,logo,group));}
        r.close();c.disconnect();if(found.isEmpty())throw new java.io.IOException("La playlist ne contient aucun flux lisible.");
      }catch(Exception e){error=e.getMessage()==null?"Connexion impossible":e.getMessage();}
      final ArrayList<Channel> result=found;final String err=error;
      runOnUiThread(()->{if(result.isEmpty())status.setText("Import impossible : "+err);else status.setText(result.size()+" chaînes importées");showChannels(result);});
    });
  }
  private String attribute(String line,String key){int a=line.indexOf(key+"=");if(a<0)return "";int start=a+key.length()+1;if(start<line.length()&&line.charAt(start)=='"')start++;int end=line.indexOf('"',start);return end>start?line.substring(start,end):"";}
  private void openVavooSettings(){
    SharedPreferences prefs=getSharedPreferences("sources",MODE_PRIVATE);
    String saved=prefs.getString("vavoo_server","");
    EditText input=new EditText(this);input.setSingleLine(true);input.setText(saved);
    input.setHint("https://serveur/playlist.m3u8");
    input.setTextColor(Color.WHITE);input.setHintTextColor(0xFF9BA7BA);
    input.setPadding(18,12,18,12);input.setSelectAllOnFocus(true);
    LinearLayout wrap=new LinearLayout(this);wrap.setPadding(24,8,24,0);wrap.addView(input,new LinearLayout.LayoutParams(-1,-2));
    new AlertDialog.Builder(this).setTitle("VAVOO · VXParser")
      .setMessage("Adresse de base d’une instance VXParser accessible. NISSKENZI essaie l’API Xtream JSON puis la playlist M3U. Le catalogue LiveTV doit être initialisé dans VXParser. Cette étape connecte l’application à VXParser ; elle n’embarque pas encore le moteur Python dans l’APK.")
      .setView(wrap)
      .setPositiveButton("CONNECTER",(d,w)->{String value=input.getText().toString().trim().replaceAll("/+$","");if(!value.isEmpty()){prefs.edit().putString("vavoo_server",value).apply();loadVavooChannels();}})
      .setNeutralButton("EFFACER",(d,w)->{prefs.edit().remove("vavoo_server").apply();Toast.makeText(this,"Adresse VAVOO effacée",Toast.LENGTH_SHORT).show();})
      .setNegativeButton("ANNULER",null).show();
  }
  private void loadVavooChannels(){
    SharedPreferences prefs=getSharedPreferences("sources",MODE_PRIVATE);
    String server=prefs.getString("vavoo_server","").trim().replaceAll("/+$","");
    if(server.isEmpty()){openVavooSettings();return;}
    status.setText("Connexion à VXParser · catalogue VAVOO…");
    io.execute(()->{
      ArrayList<Channel> found=new ArrayList<>();
      String lastError="Aucune chaîne reçue depuis VXParser.";
      try{
        String api=server+"/player_api.php?username=nissay&password=stream&action=get_live_streams";
        HttpURLConnection c=(HttpURLConnection)new URL(api).openConnection();
        c.setConnectTimeout(10000);c.setReadTimeout(25000);c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent","NissayStream/1.5");
        int code=c.getResponseCode();
        if(code<200||code>=300)throw new java.io.IOException("API VXParser : HTTP "+code);
        StringBuilder body=new StringBuilder();BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"));
        String line;while((line=r.readLine())!=null)body.append(line);r.close();c.disconnect();
        JSONArray items;String json=body.toString().trim();
        if(json.startsWith("["))items=new JSONArray(json);
        else if(json.startsWith("{")){
          JSONObject object=new JSONObject(json);
          if(object.has("data")&&object.get("data") instanceof JSONArray)items=object.getJSONArray("data");
          else {items=new JSONArray();java.util.Iterator<String> keys=object.keys();while(keys.hasNext()){Object value=object.opt(keys.next());if(value instanceof JSONObject)items.put(value);}}
        }else throw new java.io.IOException("Réponse API VXParser non reconnue.");
        for(int i=0;i<items.length();i++){
          JSONObject item=items.optJSONObject(i);if(item==null)continue;
          String id=item.optString("stream_id",item.optString("id",""));
          String name=item.optString("name",item.optString("stream_name","Chaîne VAVOO"));
          String logo=item.optString("stream_icon",item.optString("logo",""));
          String group=item.optString("category_name",item.optString("group-title","VAVOO Live"));
          String stream=item.optString("direct_source","");
          if(stream.isEmpty()&&!id.isEmpty())stream=server+"/live/nissay/stream/"+android.net.Uri.encode(id)+".m3u8";
          if(stream.startsWith("http://")||stream.startsWith("https://"))found.add(new Channel(name,stream,logo,group));
        }
        if(found.isEmpty())lastError="L’API répond, mais aucune chaîne LiveTV n’est en cache. Lance « Get LiveTV Lists » dans VXParser.";
      }catch(Exception e){lastError=e.getMessage()==null?"Connexion API impossible":e.getMessage();}
      if(found.isEmpty()){
        String[] candidates={server+"/get.php?username=nissay&password=stream&type=m3u_plus&output=hls",server+"/get.php?username=nissay&password=stream&type=m3u&output=hls"};
        for(String playlist:candidates){
          HttpURLConnection conn=null;
          try{
            conn=(HttpURLConnection)new URL(playlist).openConnection();conn.setConnectTimeout(10000);conn.setReadTimeout(25000);conn.setInstanceFollowRedirects(true);conn.setRequestProperty("User-Agent","NissayStream/1.5");
            int code=conn.getResponseCode();if(code<200||code>=300){lastError="VXParser M3U : HTTP "+code;continue;}
            BufferedReader r=new BufferedReader(new InputStreamReader(conn.getInputStream(),"UTF-8"));String ln,name="Chaîne VAVOO",logo="",group="VAVOO Live";
            while((ln=r.readLine())!=null){ln=ln.trim();if(ln.startsWith("#EXTINF:")){int comma=ln.lastIndexOf(',');name=comma>=0?ln.substring(comma+1).trim():"Chaîne VAVOO";logo=attribute(ln,"tvg-logo");group=attribute(ln,"group-title");if(group.isEmpty())group="VAVOO Live";}else if((ln.startsWith("http://")||ln.startsWith("https://"))&&!ln.startsWith("#"))found.add(new Channel(name,ln,logo,group));}
            r.close();if(!found.isEmpty()){lastError="";break;}
          }catch(Exception e){lastError=e.getMessage()==null?"Import M3U impossible":e.getMessage();}
          finally{if(conn!=null)conn.disconnect();}
        }
      }
      final ArrayList<Channel> result=found;final String error=lastError;
      runOnUiThread(()->{if(result.isEmpty()){status.setText("VAVOO / VXParser : "+error);list.removeAllViews();}else{channels.clear();channels.addAll(result);activeFilter="TOUT";showChannels(new ArrayList<>(result));status.setText(result.size()+" chaînes VAVOO chargées via VXParser");}});
    });
  }
  private void showChannels(ArrayList<Channel> result){
    if(result!=null&&!result.isEmpty()){channels.clear();channels.addAll(result);}list.removeAllViews();
    ArrayList<Channel> visible=new ArrayList<>();
    for(Channel ch:channels){if("FAVORIS".equals(activeFilter)&&!isFavorite(ch))continue;String q=searchQuery==null?"":searchQuery.trim().toLowerCase(java.util.Locale.ROOT);if(!q.isEmpty()&&!((ch.name+" "+ch.group).toLowerCase(java.util.Locale.ROOT).contains(q)))continue;visible.add(ch);}
    if(visible.isEmpty()){status.setText("Aucun contenu dans "+activeFilter+". Importe une playlist ou ajoute des favoris.");return;}
    status.setText(visible.size()+" contenus disponibles · sélectionne une carte pour regarder");
    java.util.LinkedHashMap<String,ArrayList<Channel>> sections=new java.util.LinkedHashMap<>();
    for(Channel ch:visible){String group=(ch.group==null||ch.group.trim().isEmpty())?"En direct":ch.group.trim();if(!sections.containsKey(group))sections.put(group,new ArrayList<>());sections.get(group).add(ch);}
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
        FrameLayout posterBox=new FrameLayout(this);posterBox.setBackground(rounded(count%3==0?0xFF55232B:(count%3==1?0xFF1D493D:0xFF303A50),10));LinearLayout.LayoutParams posterP=new LinearLayout.LayoutParams(-1,82);card.addView(posterBox,posterP);
        ImageView poster=new ImageView(this);poster.setScaleType(ImageView.ScaleType.FIT_CENTER);poster.setPadding(14,10,14,10);posterBox.addView(poster,new FrameLayout.LayoutParams(-1,-1));TextView placeholder=text("▶",24,0xFFDCE2EB,true);placeholder.setGravity(Gravity.CENTER);posterBox.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));if(ch.logo!=null&&!ch.logo.trim().isEmpty())loadLogo(poster,placeholder,ch.logo);
        TextView name=text(ch.name,13,Color.WHITE,true);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);LinearLayout.LayoutParams nameP=new LinearLayout.LayoutParams(-1,0,1);nameP.topMargin=8;card.addView(name,nameP);
        TextView meta=text((isFavorite(ch)?"★  ":"")+"EN DIRECT",9,isFavorite(ch)?0xFFFFD166:0xFFB7C0CF,true);LinearLayout.LayoutParams metaP=new LinearLayout.LayoutParams(-1,-2);metaP.topMargin=4;card.addView(meta,metaP);
        card.setOnLongClickListener(v->{toggleFavorite(ch);Toast.makeText(this,isFavorite(ch)?"Ajouté aux favoris":"Retiré des favoris",Toast.LENGTH_SHORT).show();showChannels(new ArrayList<>(channels));return true;});
        card.setOnClickListener(v->play(ch));card.setOnFocusChangeListener((v,focus)->card.setBackground(rounded(focus?0xFF394253:panel,12)));count++;
      }
      rail.addView(cards);sectionBox.addView(rail);list.addView(sectionBox);
    }
    if(!channels.isEmpty())list.setFocusable(false);
  }
  private void loadLogo(ImageView target,TextView fallback,String address){io.execute(()->{try{HttpURLConnection conn=(HttpURLConnection)new URL(address).openConnection();conn.setConnectTimeout(5000);conn.setReadTimeout(7000);conn.setInstanceFollowRedirects(true);conn.setRequestProperty("User-Agent","NissayStream/1.4");android.graphics.Bitmap bmp=BitmapFactory.decodeStream(conn.getInputStream());conn.disconnect();if(bmp!=null)runOnUiThread(()->{target.setImageBitmap(bmp);fallback.setVisibility(View.GONE);});}catch(Exception ignored){}});}
  private boolean isFavorite(Channel ch){return getSharedPreferences("favorites",MODE_PRIVATE).getBoolean(ch.url,false);}
  private void toggleFavorite(Channel ch){SharedPreferences p=getSharedPreferences("favorites",MODE_PRIVATE);p.edit().putBoolean(ch.url,!p.getBoolean(ch.url,false)).apply();}
  private void play(Channel ch){
    currentChannel=ch;
    if(player!=null){player.release();player=null;}root.removeAllViews();root.setPadding(0,0,0,0);root.setOrientation(LinearLayout.VERTICAL);
    LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(18,10,18,10);top.setBackgroundColor(bg);
    TextView back=actionButton("‹  CHAÎNES",panel);back.setFocusable(true);back.setOnClickListener(v->returnHome());top.addView(back);
    nowTitle=text(ch.name,17,Color.WHITE,true);nowTitle.setSingleLine(true);nowTitle.setEllipsize(TextUtils.TruncateAt.END);nowTitle.setPadding(14,0,14,0);top.addView(nowTitle,new LinearLayout.LayoutParams(0,-2,1));
    TextView favorite=actionButton(isFavorite(ch)?"★ FAVORI":"☆ FAVORI",isFavorite(ch)?0xFF775A12:panel);favorite.setOnClickListener(v->{toggleFavorite(ch);favorite.setText(isFavorite(ch)?"★ FAVORI":"☆ FAVORI");favorite.setBackground(rounded(isFavorite(ch)?0xFF775A12:panel,10));});top.addView(favorite);
    root.addView(top,new LinearLayout.LayoutParams(-1,-2));
    playerView=new PlayerView(this);playerView.setUseController(true);playerView.setKeepContentOnPlayerReset(true);playerView.setShutterBackgroundColor(Color.BLACK);root.addView(playerView,new LinearLayout.LayoutParams(-1,0,1));
    LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER);controls.setPadding(12,8,12,8);controls.setBackgroundColor(bg);
    TextView retry=actionButton("↻ RELANCER LE FLUX",panel);retry.setOnClickListener(v->startPlayback(ch));controls.addView(retry);
    TextView info=actionButton("INFO FLUX",panel);LinearLayout.LayoutParams infoP=new LinearLayout.LayoutParams(-2,-2);infoP.leftMargin=10;controls.addView(info,infoP);info.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(ch.name).setMessage("Groupe : "+ch.group+"\n\nURL : "+ch.url+"\n\nSi la lecture échoue, le flux peut être hors ligne, géobloqué ou incompatible.").setPositiveButton("OK",null).show());
    root.addView(controls,new LinearLayout.LayoutParams(-1,-2));startPlayback(ch);
  }
  private void startPlayback(Channel ch){
    if(player!=null){player.release();player=null;}
    player=new ExoPlayer.Builder(this).build();playerView.setPlayer(player);player.setMediaItem(MediaItem.fromUri(ch.url));player.addListener(new Player.Listener(){@Override public void onPlayerError(PlaybackException e){Toast.makeText(MainActivity.this,"Lecture impossible ("+e.getErrorCodeName()+"). Le flux peut être indisponible ou incompatible.",Toast.LENGTH_LONG).show();}});player.prepare();player.setPlayWhenReady(true);
  }
  private void returnHome(){if(player!=null){player.release();player=null;}currentChannel=null;buildHome();showChannels(new ArrayList<>(channels));}
  @Override public void onBackPressed(){if(player!=null){returnHome();}else super.onBackPressed();}
  @Override protected void onDestroy(){if(player!=null)player.release();io.shutdownNow();super.onDestroy();}
}
