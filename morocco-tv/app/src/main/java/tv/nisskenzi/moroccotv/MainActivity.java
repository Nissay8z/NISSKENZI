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
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.JavascriptInterface;
import android.text.TextUtils;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
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
  private final int bg=Color.rgb(5,8,16), panel=Color.rgb(17,24,39), red=Color.rgb(0,174,255), green=Color.rgb(0,98,51), violet=Color.rgb(129,68,255);
  private LinearLayout root, list; private TextView status, nowTitle; private EditText searchBox; private PlayerView playerView; private ExoPlayer player; private Channel currentChannel; private WebView homeWebView; private boolean homeWebReady=false; private String activeFilter="TOUT"; private String activeSection="Accueil"; private String searchQuery="";
  private final ArrayList<Channel> channels=new ArrayList<>(); private final ArrayList<Channel> allVavooChannels=new ArrayList<>(); private final ArrayList<Channel> baseChannels=new ArrayList<>(); private final ArrayList<Channel> importedChannels=new ArrayList<>(); private final ExecutorService io=Executors.newSingleThreadExecutor(); private final ExecutorService logoIo=Executors.newFixedThreadPool(3);
  private static class Channel { String name,url,logo,group,vavooSourceUrl,vavooCountry; boolean isVavoo; Channel(String n,String u,String l,String g){this(n,u,l,g,false,null,"");} Channel(String n,String u,String l,String g,boolean v,String source){this(n,u,l,g,v,source,"");} Channel(String n,String u,String l,String g,boolean v,String source,String country){name=n;url=u;logo=l;group=g;isVavoo=v;vavooSourceUrl=source;vavooCountry=country;} }
  private static class MediaEntry { String name,url,poster,group,type,id,extension; MediaEntry(String n,String u,String p,String g,String t,String i,String e){name=n;url=u;poster=p;group=g;type=t;id=i;extension=e;} }
  private final ArrayList<MediaEntry> movies=new ArrayList<>();
  private final ArrayList<MediaEntry> series=new ArrayList<>();
  @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); buildHome(); loadCachedChannels(); loadCachedImportedChannels(); loadChannels(); loadXtreamCatalog(); loadSavedSourcePlaylists();}
  private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
  private android.graphics.drawable.GradientDrawable rounded(int color,int radius){
    android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();
    d.setColor(color);d.setCornerRadius(radius);return d;
  }
  private TextView actionButton(String label,int color){
    TextView b=text(label,13,Color.WHITE,true);b.setGravity(Gravity.CENTER);b.setPadding(18,12,18,12);b.setBackground(rounded(color,10));b.setFocusable(true);return b;
  }
  private void buildHome(){
    homeWebReady=false;
    root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);
    homeWebView=new WebView(this);homeWebView.setBackgroundColor(bg);
    WebSettings webSettings=homeWebView.getSettings();webSettings.setJavaScriptEnabled(true);webSettings.setDomStorageEnabled(true);webSettings.setAllowFileAccess(true);webSettings.setAllowFileAccessFromFileURLs(false);webSettings.setAllowUniversalAccessFromFileURLs(false);webSettings.setSupportZoom(false);
    homeWebView.addJavascriptInterface(new HomeBridge(),"NisskenziBridge");
    homeWebView.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView view,String url){homeWebReady=true;renderChannelsToWeb();renderMediaToWeb();}});
    root.addView(homeWebView,new LinearLayout.LayoutParams(-1,0,1));
    status=text("",10,Color.WHITE,false);status.setVisibility(View.GONE);root.addView(status,new LinearLayout.LayoutParams(1,1));
    list=new LinearLayout(this);setContentView(root);
    homeWebView.loadUrl("file:///android_asset/nisskenzi_home.html");
  }
  private void loadCachedImportedChannels(){
    try{
      String raw=getSharedPreferences("catalog_cache",MODE_PRIVATE).getString("imported_channels","");
      if(raw.isEmpty())return;
      JSONArray arr=new JSONArray(raw);
      importedChannels.clear();
      for(int i=0;i<arr.length();i++){
        JSONObject o=arr.optJSONObject(i);if(o==null)continue;
        String name=o.optString("name","Chaîne importée"),url=o.optString("url",""),logo=o.optString("logo",""),group=o.optString("group","Playlist importée");
        if(url.isEmpty())continue;
        importedChannels.add(new Channel(name,url,logo,group,false,o.optString("sourceUrl",""),o.optString("country","")));
      }
    }catch(Exception ignored){}
  }
  private void saveCachedImportedChannels(){
    try{
      JSONArray arr=new JSONArray();
      for(Channel ch:importedChannels){
        JSONObject o=new JSONObject();o.put("name",ch.name==null?"Chaîne importée":ch.name);o.put("url",ch.url==null?"":ch.url);
        o.put("logo",ch.logo==null?"":ch.logo);o.put("group",ch.group==null?"Playlist importée":ch.group);
        o.put("sourceUrl",ch.vavooSourceUrl==null?"":ch.vavooSourceUrl);o.put("country",ch.vavooCountry==null?"":ch.vavooCountry);arr.put(o);
      }
      getSharedPreferences("catalog_cache",MODE_PRIVATE).edit().putString("imported_channels",arr.toString()).apply();
    }catch(Exception ignored){}
  }
  private void loadCachedChannels(){
    try{
      String raw=getSharedPreferences("catalog_cache",MODE_PRIVATE).getString("merged_channels","");
      if(raw.isEmpty())return;
      JSONArray arr=new JSONArray(raw);
      ArrayList<Channel> cached=new ArrayList<>();
      for(int i=0;i<arr.length();i++){
        JSONObject o=arr.optJSONObject(i);if(o==null)continue;
        String name=o.optString("name","Chaîne"),url=o.optString("url",""),logo=o.optString("logo",""),group=o.optString("group","Direct");
        if(url.isEmpty())continue;
        cached.add(new Channel(name,url,logo,group,o.optBoolean("isVavoo",false),o.optString("sourceUrl",""),o.optString("country","")));
      }
      if(!cached.isEmpty()){
        channels.clear();channels.addAll(cached);
        baseChannels.clear();baseChannels.addAll(cached);
        allVavooChannels.clear();allVavooChannels.addAll(cached);
        showChannels(new ArrayList<>(cached));
        status.setText(cached.size()+" chaînes en cache · actualisation en arrière-plan…");
      }
    }catch(Exception ignored){}
  }
  private void saveCachedChannels(ArrayList<Channel> items){
    try{
      JSONArray arr=new JSONArray();
      for(Channel ch:items){
        JSONObject o=new JSONObject();
        o.put("name",ch.name==null?"Chaîne":ch.name);
        o.put("url",ch.url==null?"":ch.url);
        o.put("logo",ch.logo==null?"":ch.logo);
        o.put("group",ch.group==null?"Direct":ch.group);
        o.put("isVavoo",ch.isVavoo);
        o.put("sourceUrl",ch.vavooSourceUrl==null?"":ch.vavooSourceUrl);
        o.put("country",ch.vavooCountry==null?"":ch.vavooCountry);
        arr.put(o);
      }
      getSharedPreferences("catalog_cache",MODE_PRIVATE).edit().putString("merged_channels",arr.toString()).putLong("updated_at",System.currentTimeMillis()).apply();
    }catch(Exception ignored){}
  }
  private String normalizeCountry(String raw){
    if(raw==null)return "";
    String value=raw.trim();
    if(value.isEmpty())return "";
    String first=value.split("[,;]")[0].trim();
    if(first.matches("(?i)[a-z]{2}")){
      String display=new java.util.Locale("",first.toUpperCase(java.util.Locale.ROOT)).getDisplayCountry(java.util.Locale.FRENCH);
      if(display!=null&&!display.trim().isEmpty())return display;
    }
    return first;
  }
  private void loadChannels(){
    status.setText("Chargement des catalogues VAVOO + IPTV-ORG…");
    io.execute(()->{
      ArrayList<Channel> merged=new ArrayList<>();
      ArrayList<Channel> vavooAll=new ArrayList<>();
      String vavooError="",iptvError="";
      try{
        for(VavooProvider.VavooChannel item:VavooProvider.loadChannels()){
          Channel ch=new Channel(item.name,"vavoo://"+item.id,item.logo,
            "VAVOO · "+(item.group==null||item.group.trim().isEmpty()?"Direct":item.group),
            true,item.sourceUrl,item.country);
          vavooAll.add(ch);
          merged.add(ch);
        }
      }catch(Exception e){vavooError=e.getMessage()==null?"catalogue VAVOO indisponible":e.getMessage();}
      try{
        URL u=new URL("https://iptv-org.github.io/iptv/index.country.m3u");
        HttpURLConnection conn=(HttpURLConnection)u.openConnection();
        conn.setConnectTimeout(15000);conn.setReadTimeout(35000);
        conn.setRequestProperty("User-Agent","NISSKENZI+/1.6");
        BufferedReader reader=new BufferedReader(new InputStreamReader(conn.getInputStream(),"UTF-8"));
        String line,name="Chaîne",logo="",group="Pays non précisé",country="Pays non précisé";
        while((line=reader.readLine())!=null){
          if(line.startsWith("#EXTINF:")){
            int comma=line.lastIndexOf(',');
            name=comma>=0?line.substring(comma+1).trim():"Chaîne";
            logo="";group="Pays non précisé";country=normalizeCountry(attribute(line,"tvg-country"));if(country.isEmpty())country=normalizeCountry(attribute(line,"country"));if(country.isEmpty())country="Pays non précisé";
            int a=line.indexOf("tvg-logo=\"");
            if(a>=0){int z=line.indexOf('"',a+10);if(z>a)logo=line.substring(a+10,z);}
            int g=line.indexOf("group-title=\"");
            if(g>=0){int z=line.indexOf('"',g+13);if(z>g)group=line.substring(g+13,z);}
          }else if(line.startsWith("http://")||line.startsWith("https://")){
            String url=line.trim();
            merged.add(new Channel(name,url,logo,"IPTV-ORG · "+group,false,null,country));
          }
        }
        reader.close();conn.disconnect();
      }catch(Exception e){iptvError=e.getMessage()==null?"playlist IPTV-ORG indisponible":e.getMessage();}
      final ArrayList<Channel> all=new ArrayList<>(merged);
      final ArrayList<Channel> vAll=new ArrayList<>(vavooAll);
      final String ve=vavooError,ie=iptvError;
      runOnUiThread(()->{
        baseChannels.clear();baseChannels.addAll(all);
        ArrayList<Channel> combined=mergeChannels(baseChannels,importedChannels);
        allVavooChannels.clear();allVavooChannels.addAll(combined);
        channels.clear();channels.addAll(combined);
        if(!combined.isEmpty())saveCachedChannels(combined);
        activeFilter="TOUT";
        showChannels(new ArrayList<>(combined));
        String msg=combined.size()+" chaînes réunies";
        if(!ve.isEmpty())msg+=" · VAVOO indisponible";
        if(!ie.isEmpty())msg+=" · IPTV-ORG indisponible";
        status.setText(msg);
        if(combined.isEmpty())Toast.makeText(this,"Aucun catalogue chargé. VAVOO : "+ve+" · IPTV-ORG : "+ie,Toast.LENGTH_LONG).show();
      });
    });
  }
  private void showSectionInfo(String section){
    list.removeAllViews();
    LinearLayout panelBox=new LinearLayout(this);panelBox.setOrientation(LinearLayout.VERTICAL);panelBox.setPadding(24,26,24,26);panelBox.setBackground(rounded(0xFF111827,20));
    TextView icon=text(section.equals("Films")?"▶":section.equals("Séries")?"▤":section.equals("Animés")?"✦":section.equals("Sport")?"⚽":"☁",32,0xFF6D5BFF,true);panelBox.addView(icon);
    TextView title=text(section,26,Color.WHITE,true);LinearLayout.LayoutParams titleP=new LinearLayout.LayoutParams(-1,-2);titleP.topMargin=10;panelBox.addView(title,titleP);
    TextView desc=text("Cette rubrique est prête à accueillir son catalogue, mais aucune source de "+section.toLowerCase(java.util.Locale.ROOT)+" n’est connectée pour le moment. Connecte une playlist ou un service que tu es autorisé à utiliser.",14,0xFFB7C0CF,false);desc.setLineSpacing(3,1f);LinearLayout.LayoutParams descP=new LinearLayout.LayoutParams(-1,-2);descP.topMargin=8;panelBox.addView(desc,descP);
    TextView connect=actionButton("＋ CONNECTER UNE SOURCE",0xFF3921A8);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.topMargin=18;panelBox.addView(connect,cp);connect.setOnClickListener(v->openSourceMenu());
    TextView live=actionButton("RETOUR AU DIRECT",red);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=8;panelBox.addView(live,lp);live.setOnClickListener(v->{activeSection="Live TV";activeFilter="TOUT";showChannels(new ArrayList<>(channels));});
    list.addView(panelBox);status.setText("Rubrique "+section+" · source nécessaire");
  }
  private void openCloudInfo(){
    new AlertDialog.Builder(this).setTitle("NISSKENZI+ · Cloud").setMessage("La rubrique Cloud pourra afficher les fichiers locaux et les sources cloud compatibles après connexion. Aucune connexion cloud n’est configurée dans cette version.").setPositiveButton("OK",null).setNegativeButton("Connecter une playlist",(d,w)->openSourceMenu()).show();
  }
  private String xtreamUrl(String host,String user,String pass,String action){
    return host.replaceAll("/+$","")+"/player_api.php?username="+android.net.Uri.encode(user)+"&password="+android.net.Uri.encode(pass)+(action==null||action.isEmpty()?"":"&action="+android.net.Uri.encode(action));
  }
  private JSONArray requestJsonArray(String address) throws Exception{
    HttpURLConnection conn=(HttpURLConnection)new URL(address).openConnection();
    conn.setConnectTimeout(10000);conn.setReadTimeout(18000);conn.setRequestProperty("User-Agent","NISSKENZI+/1.7");
    try{
      int code=conn.getResponseCode();if(code<200||code>=300)throw new java.io.IOException("HTTP "+code);
      BufferedReader reader=new BufferedReader(new InputStreamReader(conn.getInputStream(),"UTF-8"));
      StringBuilder body=new StringBuilder();String line;while((line=reader.readLine())!=null)body.append(line);reader.close();
      String raw=body.toString().trim();if(!raw.startsWith("["))throw new java.io.IOException("Réponse API invalide");return new JSONArray(raw);
    }finally{conn.disconnect();}
  }
  private java.util.HashMap<String,String> xtreamCategories(String host,String user,String pass,String action){
    java.util.HashMap<String,String> result=new java.util.HashMap<>();
    try{JSONArray array=requestJsonArray(xtreamUrl(host,user,pass,action));for(int i=0;i<array.length();i++){JSONObject o=array.optJSONObject(i);if(o!=null)result.put(o.optString("category_id",""),o.optString("category_name","Autres"));}}catch(Exception ignored){}
    return result;
  }
  private void loadXtreamCatalog(){
    SharedPreferences prefs=getSharedPreferences("sources",MODE_PRIVATE);
    String host=prefs.getString("xc_host","").trim().replaceAll("/+$",""),user=prefs.getString("xc_user","").trim(),pass=prefs.getString("xc_pass","");
    if(host.isEmpty()||user.isEmpty()||pass.isEmpty())return;
    status.setText("Chargement des catalogues Films et Séries…");
    io.execute(()->{
      ArrayList<MediaEntry> newMovies=new ArrayList<>(),newSeries=new ArrayList<>();String error="";
      try{
        java.util.HashMap<String,String> movieCategories=xtreamCategories(host,user,pass,"get_vod_categories");
        java.util.HashMap<String,String> seriesCategories=xtreamCategories(host,user,pass,"get_series_categories");
        JSONArray vod=requestJsonArray(xtreamUrl(host,user,pass,"get_vod_streams"));
        for(int i=0;i<vod.length()&&newMovies.size()<700;i++){
          JSONObject o=vod.optJSONObject(i);if(o==null)continue;
          String id=o.optString("stream_id","");if(id.isEmpty())continue;
          String name=o.optString("name","Film");String ext=o.optString("container_extension","mp4");
          String cat=(movieCategories.containsKey(o.optString("category_id",""))?movieCategories.get(o.optString("category_id","")):"Films");
          String url=host+"/movie/"+android.net.Uri.encode(user)+"/"+android.net.Uri.encode(pass)+"/"+id+"."+(ext.isEmpty()?"mp4":ext);
          newMovies.add(new MediaEntry(name,url,o.optString("stream_icon",""),cat,"movie",id,ext));
        }
        JSONArray tv=requestJsonArray(xtreamUrl(host,user,pass,"get_series"));
        for(int i=0;i<tv.length()&&newSeries.size()<700;i++){
          JSONObject o=tv.optJSONObject(i);if(o==null)continue;
          String id=o.optString("series_id","");if(id.isEmpty())continue;
          String cat=(seriesCategories.containsKey(o.optString("category_id",""))?seriesCategories.get(o.optString("category_id","")):"Séries");
          newSeries.add(new MediaEntry(o.optString("name","Série"),"",o.optString("cover",""),cat,"series",id,""));
        }
      }catch(Exception e){error=e.getMessage()==null?"API Xtream indisponible":e.getMessage();}
      final ArrayList<MediaEntry> m=newMovies,s=newSeries;final String err=error;
      runOnUiThread(()->{
        movies.clear();movies.addAll(m);series.clear();series.addAll(s);renderMediaToWeb();
        if(m.isEmpty()&&s.isEmpty()&&!err.isEmpty())status.setText("Catalogues VOD indisponibles : "+err);
        else status.setText(m.size()+" films · "+s.size()+" séries chargés depuis Xtream");
      });
    });
  }
  private void openSeriesEpisodes(MediaEntry item){
    SharedPreferences prefs=getSharedPreferences("sources",MODE_PRIVATE);
    String host=prefs.getString("xc_host","").trim().replaceAll("/+$",""),user=prefs.getString("xc_user","").trim(),pass=prefs.getString("xc_pass","");
    if(host.isEmpty()||user.isEmpty()||pass.isEmpty()){new AlertDialog.Builder(this).setTitle(item.name).setMessage("Connecte un compte Xtream compatible pour charger les épisodes de cette série.").setPositiveButton("OK",null).show();return;}
    status.setText("Chargement des épisodes de "+item.name+"…");
    io.execute(()->{
      ArrayList<MediaEntry> eps=new ArrayList<>();String err="";
      try{
        String address=xtreamUrl(host,user,pass,"get_series_info")+"&series_id="+android.net.Uri.encode(item.id);
        HttpURLConnection conn=(HttpURLConnection)new URL(address).openConnection();conn.setConnectTimeout(10000);conn.setReadTimeout(18000);conn.setRequestProperty("User-Agent","NISSKENZI+/1.7");
        JSONObject rootObj;
        try{int code=conn.getResponseCode();if(code<200||code>=300)throw new java.io.IOException("HTTP "+code);BufferedReader r=new BufferedReader(new InputStreamReader(conn.getInputStream(),"UTF-8"));StringBuilder b=new StringBuilder();String line;while((line=r.readLine())!=null)b.append(line);r.close();rootObj=new JSONObject(b.toString());}finally{conn.disconnect();}
        JSONObject episodeMap=rootObj.optJSONObject("episodes");
        if(episodeMap!=null){java.util.Iterator<String> seasons=episodeMap.keys();while(seasons.hasNext()){String season=seasons.next();JSONArray arr=episodeMap.optJSONArray(season);if(arr==null)continue;for(int i=0;i<arr.length();i++){JSONObject ep=arr.optJSONObject(i);if(ep==null)continue;String id=ep.optString("id","");if(id.isEmpty())continue;String ext=ep.optString("container_extension","mp4");String label=ep.optString("title","Épisode "+ep.optString("episode_num",String.valueOf(i+1)));String stream=host+"/series/"+android.net.Uri.encode(user)+"/"+android.net.Uri.encode(pass)+"/"+id+"."+(ext.isEmpty()?"mp4":ext);eps.add(new MediaEntry(label,stream,ep.optString("info",""),"Saison "+season,"episode",id,ext));}}}
        if(eps.isEmpty())throw new java.io.IOException("Aucun épisode n’a été retourné par la source.");
      }catch(Exception e){err=e.getMessage()==null?"Impossible de charger les épisodes":e.getMessage();}
      final ArrayList<MediaEntry> items=eps;final String error=err;
      runOnUiThread(()->{
        if(items.isEmpty()){new AlertDialog.Builder(this).setTitle(item.name).setMessage("Épisodes indisponibles : "+error).setPositiveButton("OK",null).show();return;}
        String[] labels=new String[items.size()];for(int i=0;i<items.size();i++)labels[i]=items.get(i).group+" · "+items.get(i).name;
        new AlertDialog.Builder(this).setTitle(item.name).setItems(labels,(d,which)->{MediaEntry ep=items.get(which);play(new Channel(item.name+" — "+ep.name,ep.url,item.poster,"Séries · "+ep.group));}).setNegativeButton("Fermer",null).show();
      });
    });
  }
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
      loadImportedPlaylist(h+"/get.php?username="+android.net.Uri.encode(u)+"&password="+android.net.Uri.encode(pw)+"&type=m3u_plus&output=ts");loadXtreamCatalog();
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
  private void rememberPlaylist(String playlist){
    SharedPreferences prefs=getSharedPreferences("sources",MODE_PRIVATE);
    java.util.HashSet<String> urls=new java.util.HashSet<>(prefs.getStringSet("user_playlist_urls",java.util.Collections.emptySet()));
    urls.add(playlist);
    prefs.edit().putStringSet("user_playlist_urls",urls).putString("last_imported_playlist",playlist).apply();
  }
  private void loadSavedSourcePlaylists(){
    SharedPreferences prefs=getSharedPreferences("sources",MODE_PRIVATE);
    java.util.Set<String> saved=prefs.getStringSet("user_playlist_urls",null);
    java.util.ArrayList<String> urls=new java.util.ArrayList<>();
    if(saved!=null)urls.addAll(saved);
    if(urls.isEmpty()){
      String legacy=prefs.getString("last_imported_playlist","");
      if(!legacy.isEmpty())urls.add(legacy);
    }
    for(String url:urls)if(url!=null&&!url.trim().isEmpty())loadImportedPlaylist(url);
  }
  private void loadImportedPlaylist(String playlist){
    status.setText("Chargement de la playlist…");
    io.execute(()->{
      ArrayList<Channel> found=new ArrayList<>();String error="";
      try{
        HttpURLConnection c=(HttpURLConnection)new URL(playlist).openConnection();c.setConnectTimeout(12000);c.setReadTimeout(20000);c.setRequestProperty("User-Agent","NissayStream/1.3");
        int code=c.getResponseCode();if(code<200||code>=300)throw new java.io.IOException("HTTP "+code);
        BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"));String line,name="Chaîne importée",logo="",group="Playlist importée";
        while((line=r.readLine())!=null){line=line.trim();if(line.startsWith("#EXTINF:")){int comma=line.lastIndexOf(',');name=comma>=0?line.substring(comma+1).trim():"Chaîne importée";logo="";group="Playlist importée";logo=attribute(line,"tvg-logo");group=attribute(line,"group-title");}else if((line.startsWith("http://")||line.startsWith("https://"))&&!line.startsWith("#"))found.add(new Channel(name,line,logo,group,false,playlist,""));}
        r.close();c.disconnect();if(found.isEmpty())throw new java.io.IOException("La playlist ne contient aucun flux lisible.");
      }catch(Exception e){error=e.getMessage()==null?"Connexion impossible":e.getMessage();}
      final ArrayList<Channel> result=found;final String err=error;
      runOnUiThread(()->{
        if(result.isEmpty()){status.setText("Import impossible : "+err);return;}
        for(java.util.Iterator<Channel> it=importedChannels.iterator();it.hasNext();){Channel existing=it.next();if(playlist.equals(existing.vavooSourceUrl))it.remove();}
        importedChannels.addAll(result);
        saveCachedImportedChannels();
        rememberPlaylist(playlist);
        ArrayList<Channel> base=new ArrayList<>(baseChannels.isEmpty()?allVavooChannels:baseChannels);
        ArrayList<Channel> combined=mergeChannels(base,importedChannels);
        allVavooChannels.clear();allVavooChannels.addAll(combined);
        channels.clear();channels.addAll(combined);saveCachedChannels(combined);
        status.setText(result.size()+" chaînes importées · "+combined.size()+" au total");
        showChannels(new ArrayList<>(combined));
      });
    });
  }
  private String attribute(String line,String key){int a=line.indexOf(key+"=");if(a<0)return "";int start=a+key.length()+1;if(start<line.length()&&line.charAt(start)=='"')start++;int end=line.indexOf('"',start);return end>start?line.substring(start,end):"";}
  private void openVavooSettings(){
    String[] labels={"France","Allemagne","Espagne","Italie","Royaume-Uni","Tous les pays"};
    String[] catalogCountries={"France","Germany","Spain","Italy","United Kingdom","*"};
    SharedPreferences prefs=getSharedPreferences("sources",MODE_PRIVATE);
    String saved=prefs.getString("vavoo_country_filter","France");
    int selected=0;
    for(int i=0;i<labels.length;i++)if(labels[i].equals(saved)){selected=i;break;}
    new AlertDialog.Builder(this).setTitle("VAVOO · pays du catalogue")
      .setSingleChoiceItems(labels,selected,(dialog,which)->{
        prefs.edit().putString("vavoo_country_filter",labels[which]).apply();
        dialog.dismiss();
        loadVavooChannels();
      })
      .setNegativeButton("Fermer",null).show();
  }
  private void loadVavooChannels(){ loadChannels(); }
  private ArrayList<Channel> mergeChannels(java.util.List<Channel> first,java.util.List<Channel> second){
    ArrayList<Channel> out=new ArrayList<>();java.util.HashSet<String> seen=new java.util.HashSet<>();
    if(first!=null)for(Channel ch:first){String key=ch.url==null?ch.name:ch.url;if(key!=null&&seen.add(key))out.add(ch);}
    if(second!=null)for(Channel ch:second){String key=ch.url==null?ch.name:ch.url;if(key!=null&&seen.add(key))out.add(ch);}
    return out;
  }
  private void showChannels(ArrayList<Channel> result){
    if(result!=null){channels.clear();channels.addAll(result);}
    renderChannelsToWeb();
  }
  private void renderChannelsToWeb(){
    if(homeWebView==null||!homeWebReady)return;
    try{
      homeWebView.evaluateJavascript("beginChannels();",null);
      final int chunkSize=300;
      for(int start=0;start<channels.size();start+=chunkSize){
        JSONArray chunk=new JSONArray();
        int end=Math.min(channels.size(),start+chunkSize);
        for(int i=start;i<end;i++){
          Channel ch=channels.get(i);JSONObject o=new JSONObject();
          o.put("name",ch.name==null?"Chaîne":ch.name);o.put("url",ch.url==null?"":ch.url);
          o.put("logo",ch.logo==null?"":ch.logo);o.put("group",ch.group==null?"Direct":ch.group);
          o.put("country",ch.vavooCountry==null||ch.vavooCountry.trim().isEmpty()?"Pays non précisé":ch.vavooCountry);
          o.put("isVavoo",ch.isVavoo);o.put("favorite",isFavorite(ch));chunk.put(o);
        }
        homeWebView.evaluateJavascript("appendChannelChunk("+JSONObject.quote(chunk.toString())+");",null);
      }
      homeWebView.evaluateJavascript("finishChannels();",null);
    }catch(Exception ignored){}
  }
  private void renderMediaToWeb(){
    if(homeWebView==null||!homeWebReady)return;
    try{
      JSONArray m=new JSONArray(),s=new JSONArray();
      for(MediaEntry x:movies){JSONObject o=new JSONObject();o.put("name",x.name);o.put("url",x.url);o.put("poster",x.poster);o.put("group",x.group);o.put("type",x.type);o.put("id",x.id);m.put(o);}
      for(MediaEntry x:series){JSONObject o=new JSONObject();o.put("name",x.name);o.put("url",x.url);o.put("poster",x.poster);o.put("group",x.group);o.put("type",x.type);o.put("id",x.id);mSafePut(s,o);}
      homeWebView.evaluateJavascript("receiveMedia("+JSONObject.quote(m.toString())+","+JSONObject.quote(s.toString())+");",null);
    }catch(Exception ignored){}
  }
  private void mSafePut(JSONArray array,JSONObject value){array.put(value);}
  public final class HomeBridge {
    @JavascriptInterface public void ready(){runOnUiThread(()->{homeWebReady=true;renderChannelsToWeb();renderMediaToWeb();});}
    @JavascriptInterface public void refresh(){runOnUiThread(()->loadChannels());}
    @JavascriptInterface public void addSource(){runOnUiThread(()->openSourceMenu());}
    @JavascriptInterface public void settings(){runOnUiThread(()->new AlertDialog.Builder(MainActivity.this).setTitle("Paramètres NISSKENZI+").setItems(new String[]{"Ajouter / importer une source","Pays du catalogue VAVOO","Actualiser les catalogues"},(d,w)->{if(w==0)openSourceMenu();else if(w==1)openVavooSettings();else{MainActivity.this.loadChannels();MainActivity.this.loadXtreamCatalog();}}).setNegativeButton("Fermer",null).show());}
    @JavascriptInterface public void refreshStatus(String message){runOnUiThread(()->status.setText(message));}
    @JavascriptInterface public void playChannel(int index){runOnUiThread(()->{if(index>=0&&index<channels.size())play(channels.get(index));});}
    @JavascriptInterface public void toggleFavorite(int index){runOnUiThread(()->{if(index>=0&&index<channels.size()){MainActivity.this.toggleFavorite(channels.get(index));if(homeWebView!=null&&homeWebReady)homeWebView.evaluateJavascript("updateChannelFavorite("+index+","+isFavorite(channels.get(index))+");",null);}});}
    @JavascriptInterface public void playMedia(String type,int index){runOnUiThread(()->{if("Films".equals(type)){if(index>=0&&index<movies.size()){MediaEntry x=movies.get(index);MainActivity.this.play(new Channel(x.name,x.url,x.poster,"Films · "+x.group));}}else if(index>=0&&index<series.size())MainActivity.this.openSeriesEpisodes(series.get(index));});}
  }
  private void loadLogo(ImageView target,TextView fallback,String address){logoIo.execute(()->{try{HttpURLConnection conn=(HttpURLConnection)new URL(address).openConnection();conn.setConnectTimeout(5000);conn.setReadTimeout(7000);conn.setInstanceFollowRedirects(true);conn.setRequestProperty("User-Agent","NissayStream/1.4");android.graphics.Bitmap bmp=BitmapFactory.decodeStream(conn.getInputStream());conn.disconnect();if(bmp!=null)runOnUiThread(()->{target.setImageBitmap(bmp);fallback.setVisibility(View.GONE);});}catch(Exception ignored){}});}
  private boolean isFavorite(Channel ch){return getSharedPreferences("favorites",MODE_PRIVATE).getBoolean(ch.url,false);}
  private void toggleFavorite(Channel ch){SharedPreferences p=getSharedPreferences("favorites",MODE_PRIVATE);p.edit().putBoolean(ch.url,!p.getBoolean(ch.url,false)).apply();}
  private FrameLayout playerStage;
  private LinearLayout channelDrawer;
  private boolean countryDrawerOpen=false;
  private void play(Channel ch){
    currentChannel=ch;
    if(homeWebView!=null){try{homeWebView.stopLoading();homeWebView.removeJavascriptInterface("NisskenziBridge");homeWebView.destroy();}catch(Exception ignored){}homeWebView=null;homeWebReady=false;}
    if(player!=null){player.release();player=null;}
    root.removeAllViews();root.setPadding(0,0,0,0);root.setOrientation(LinearLayout.VERTICAL);
    LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(18,10,18,10);top.setBackgroundColor(0xFF090B10);
    TextView back=actionButton("‹  ACCUEIL",panel);back.setFocusable(true);back.setOnClickListener(v->returnHome());top.addView(back);
    nowTitle=text(ch.name,17,Color.WHITE,true);nowTitle.setSingleLine(true);nowTitle.setEllipsize(TextUtils.TruncateAt.END);nowTitle.setPadding(14,0,14,0);top.addView(nowTitle,new LinearLayout.LayoutParams(0,-2,1));
    TextView browse=actionButton("☷  CHAÎNES",0xFF202631);browse.setOnClickListener(v->showChannelDrawer(false));top.addView(browse);
    TextView favorite=actionButton(isFavorite(ch)?"★":"☆",isFavorite(ch)?0xFF775A12:panel);LinearLayout.LayoutParams favP=new LinearLayout.LayoutParams(-2,-2);favP.leftMargin=8;top.addView(favorite,favP);favorite.setOnClickListener(v->{toggleFavorite(ch);favorite.setText(isFavorite(ch)?"★":"☆");favorite.setBackground(rounded(isFavorite(ch)?0xFF775A12:panel,10));});
    root.addView(top,new LinearLayout.LayoutParams(-1,-2));
    playerStage=new FrameLayout(this);playerStage.setBackgroundColor(Color.BLACK);
    playerView=new PlayerView(this);playerView.setUseController(true);playerView.setKeepContentOnPlayerReset(true);playerView.setShutterBackgroundColor(Color.BLACK);
    playerStage.addView(playerView,new FrameLayout.LayoutParams(-1,-1));
    root.addView(playerStage,new LinearLayout.LayoutParams(-1,0,1));
    LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER_VERTICAL);controls.setPadding(18,8,18,8);controls.setBackgroundColor(0xFF090B10);
    boolean isVod=ch.group!=null&&(ch.group.startsWith("Films ·")||ch.group.startsWith("Séries ·"));
    TextView live=text(isVod?"▶  LECTURE VOD":"●  EN DIRECT",11,isVod?0xFF69D6FF:0xFFFF4D5A,true);controls.addView(live,new LinearLayout.LayoutParams(0,-2,1));
    TextView retry=actionButton("↻ RELANCER",panel);retry.setOnClickListener(v->startPlayback(ch));controls.addView(retry);
    TextView info=actionButton("ⓘ",panel);LinearLayout.LayoutParams infoP=new LinearLayout.LayoutParams(-2,-2);infoP.leftMargin=8;controls.addView(info,infoP);info.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(ch.name).setMessage("Groupe : "+ch.group+"\n\nSource : "+(ch.isVavoo?"VAVOO":"flux externe")+"\n\nLa disponibilité dépend de la source et de ses conditions d’accès.").setPositiveButton("OK",null).show());
    root.addView(controls,new LinearLayout.LayoutParams(-1,-2));
    startPlayback(ch);
  }
  private void showChannelDrawer(boolean countries){
    if(playerStage==null||player==null)return;
    if(channelDrawer!=null){playerStage.removeView(channelDrawer);channelDrawer=null;}
    countryDrawerOpen=countries;
    channelDrawer=new LinearLayout(this);channelDrawer.setOrientation(LinearLayout.VERTICAL);channelDrawer.setPadding(18,20,18,18);channelDrawer.setBackground(rounded(0xF20D1119,18));
    TextView heading=text(countries?"PAYS DU DIRECT":"CHAÎNES EN DIRECT",16,Color.WHITE,true);channelDrawer.addView(heading);
    TextView hint=text(countries?"Choisis une région":"← encore : parcourir les pays",11,0xFF9CA8B9,false);LinearLayout.LayoutParams hintP=new LinearLayout.LayoutParams(-1,-2);hintP.topMargin=5;channelDrawer.addView(hint,hintP);
    ScrollView scroll=new ScrollView(this);LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);rows.setPadding(0,12,0,8);scroll.addView(rows);channelDrawer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    if(countries){
      java.util.LinkedHashSet<String> countriesList=new java.util.LinkedHashSet<>();
      for(Channel item:allVavooChannels){String country=item.vavooCountry;if(country!=null&&!country.trim().isEmpty())countriesList.add(country);}
      countriesList.add("Tous les pays");
      if(countriesList.isEmpty())for(String country:new String[]{"France","Germany","Spain","Italy","United Kingdom","Tous les pays"})countriesList.add(country);
      for(String country:countriesList){
        TextView row=drawerRow(country,"⌖");
        row.setOnClickListener(v->{String filter=country;getSharedPreferences("sources",MODE_PRIVATE).edit().putString("vavoo_country_filter",filter).apply();channels.clear();if(filter.equalsIgnoreCase("Tous les pays"))channels.addAll(allVavooChannels);else for(Channel item:allVavooChannels){String itemCountry=item.vavooCountry==null?"":item.vavooCountry;if(itemCountry.equalsIgnoreCase(filter)||item.group.equalsIgnoreCase("IPTV-ORG · "+filter))channels.add(item);}if(channelDrawer!=null)playerStage.removeView(channelDrawer);channelDrawer=null;showChannels(new ArrayList<>(channels));});
        rows.addView(row);
      }
    }else{
      ArrayList<Channel> available=new ArrayList<>();
      for(Channel item:channels)if(!item.isVavoo||item.isVavoo)available.add(item);
      for(Channel item:available){
        TextView row=drawerRow(item.name,item==currentChannel?"▶":"•");
        row.setOnClickListener(v->{if(channelDrawer!=null)playerStage.removeView(channelDrawer);channelDrawer=null;play(item);});
        rows.addView(row);
      }
    }
    TextView close=actionButton("FERMER",0xFF252B36);LinearLayout.LayoutParams closeP=new LinearLayout.LayoutParams(-1,-2);closeP.topMargin=8;channelDrawer.addView(close,closeP);close.setOnClickListener(v->{playerStage.removeView(channelDrawer);channelDrawer=null;});
    FrameLayout.LayoutParams drawerP=new FrameLayout.LayoutParams((int)(getResources().getDisplayMetrics().widthPixels*0.82f),-1,Gravity.LEFT);
    drawerP.setMargins(10,10,0,10);playerStage.addView(channelDrawer,drawerP);
    channelDrawer.setFocusableInTouchMode(true);channelDrawer.requestFocus();
  }
  private TextView drawerRow(String title,String iconText){
    TextView row=text(iconText+"   "+title,14,Color.WHITE,true);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(14,14,12,14);row.setSingleLine(true);row.setEllipsize(TextUtils.TruncateAt.END);row.setFocusable(true);row.setBackground(rounded(0xFF1B212C,10));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=6;row.setLayoutParams(p);row.setOnFocusChangeListener((v,focus)->row.setBackground(rounded(focus?0xFF3A4558:0xFF1B212C,10)));return row;
  }

  private void startPlayback(Channel ch){
    if(player!=null){player.release();player=null;}
    DefaultHttpDataSource.Factory dataSourceFactory=new DefaultHttpDataSource.Factory()
      .setUserAgent(ch.isVavoo?"VAVOO/2.6":"NissayStream/1.5")
      .setAllowCrossProtocolRedirects(true);
    ExoPlayer activePlayer=new ExoPlayer.Builder(this)
      .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory)).build();
    player=activePlayer;
    playerView.setPlayer(activePlayer);
    activePlayer.addListener(new Player.Listener(){
      @Override public void onPlayerError(PlaybackException e){
        Toast.makeText(MainActivity.this,"Lecture impossible ("+e.getErrorCodeName()+"). Le flux peut être indisponible ou incompatible.",Toast.LENGTH_LONG).show();
      }
    });
    if(ch.isVavoo){
      Toast.makeText(this,"Résolution du flux VAVOO…",Toast.LENGTH_SHORT).show();
      io.execute(()->{
        try{
          String resolved=VavooProvider.resolveStream(ch.vavooSourceUrl);
          runOnUiThread(()->{
            if(player!=activePlayer||currentChannel!=ch)return;
            activePlayer.setMediaItem(MediaItem.fromUri(resolved));
            activePlayer.prepare();
            activePlayer.setPlayWhenReady(true);
          });
        }catch(Exception e){
          String message=e.getMessage()==null?"Résolution VAVOO impossible":e.getMessage();
          runOnUiThread(()->{if(player==activePlayer)Toast.makeText(MainActivity.this,"VAVOO : "+message,Toast.LENGTH_LONG).show();});
        }
      });
    }else{
      activePlayer.setMediaItem(MediaItem.fromUri(ch.url));
      activePlayer.prepare();
      activePlayer.setPlayWhenReady(true);
    }
  }
  private void returnHome(){if(player!=null){player.release();player=null;}playerStage=null;channelDrawer=null;countryDrawerOpen=false;currentChannel=null;buildHome();showChannels(new ArrayList<>(channels));}
  @Override public boolean dispatchKeyEvent(android.view.KeyEvent event){
    if(event.getAction()==android.view.KeyEvent.ACTION_DOWN&&event.getKeyCode()==android.view.KeyEvent.KEYCODE_DPAD_LEFT&&player!=null){
      if(channelDrawer!=null){if(!countryDrawerOpen)showChannelDrawer(true);else{playerStage.removeView(channelDrawer);channelDrawer=null;countryDrawerOpen=false;}}
      else showChannelDrawer(false);
      return true;
    }
    if(event.getAction()==android.view.KeyEvent.ACTION_DOWN&&event.getKeyCode()==android.view.KeyEvent.KEYCODE_DPAD_RIGHT&&channelDrawer!=null){
      playerStage.removeView(channelDrawer);channelDrawer=null;countryDrawerOpen=false;return true;
    }
    return super.dispatchKeyEvent(event);
  }
  @Override public void onBackPressed(){if(channelDrawer!=null&&playerStage!=null){playerStage.removeView(channelDrawer);channelDrawer=null;countryDrawerOpen=false;}else if(player!=null){returnHome();}else super.onBackPressed();}
  @Override protected void onDestroy(){if(player!=null)player.release();if(homeWebView!=null){try{homeWebView.removeJavascriptInterface("NisskenziBridge");homeWebView.destroy();}catch(Exception ignored){}}io.shutdownNow();logoIo.shutdownNow();super.onDestroy();}
}
