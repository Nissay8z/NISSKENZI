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
import androidx.media3.datasource.DefaultDataSource;
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
  private LinearLayout root; private TextView status, nowTitle; private PlayerView playerView; private ExoPlayer player; private Channel currentChannel; private WebView homeWebView; private boolean homeWebReady=false; private volatile String activeSection="Live TV"; private volatile String catalogStatus="Chargement des catalogues VAVOO + IPTV-ORG…";
  private final ArrayList<Channel> channels=new ArrayList<>(); private final ExecutorService io=Executors.newSingleThreadExecutor();
  private static class Channel { String name,url,logo,group,vavooSourceUrl,vavooCountry; boolean isVavoo; Channel(String n,String u,String l,String g){this(n,u,l,g,false,null,"");} Channel(String n,String u,String l,String g,boolean v,String source){this(n,u,l,g,v,source,"");} Channel(String n,String u,String l,String g,boolean v,String source,String country){name=n;url=u;logo=l;group=g;isVavoo=v;vavooSourceUrl=source;vavooCountry=country;} }
  private TextView actionButton(String label,int color){
    TextView button=text(label,13,Color.WHITE,true);
    button.setGravity(Gravity.CENTER);
    button.setPadding(18,12,18,12);
    button.setBackground(rounded(color,10));
    button.setFocusable(true);
    return button;
  }
  @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); buildHome(); loadCachedChannels(); loadChannels();}
  private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
  private android.graphics.drawable.GradientDrawable rounded(int color,int radius){
    android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();
    d.setColor(color);d.setCornerRadius(radius);return d;
  }
  private void buildHome(){
    activeSection="Live TV";homeWebReady=false;
    root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);
    homeWebView=new WebView(this);homeWebView.setBackgroundColor(bg);
    WebSettings webSettings=homeWebView.getSettings();webSettings.setJavaScriptEnabled(true);webSettings.setDomStorageEnabled(true);webSettings.setAllowFileAccess(true);webSettings.setAllowFileAccessFromFileURLs(false);webSettings.setAllowUniversalAccessFromFileURLs(false);webSettings.setSupportZoom(false);
    homeWebView.addJavascriptInterface(new HomeBridge(),"NisskenziBridge");
    homeWebView.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView view,String url){homeWebReady=true;renderChannelsToWeb();}});
    root.addView(homeWebView,new LinearLayout.LayoutParams(-1,0,1));
    status=text("",10,Color.WHITE,false);status.setVisibility(View.GONE);root.addView(status,new LinearLayout.LayoutParams(1,1));
    setContentView(root);
    homeWebView.loadUrl("file:///android_asset/nisskenzi_home.html");
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
        boolean cachedVavoo=o.optBoolean("isVavoo",false);
        if(!group.startsWith("VAVOO · ")&&!group.startsWith("IPTV-ORG · "))continue;
        String cachedCountry=normalizeCountry(o.optString("country",""));
        if(cachedCountry.isEmpty()||"Pays non précisé".equalsIgnoreCase(cachedCountry)||"default".equalsIgnoreCase(cachedCountry)){
          String prefix=cachedVavoo?"VAVOO · ":"IPTV-ORG · ";
          String derived=group.startsWith(prefix)?group.substring(prefix.length()):group;
          cachedCountry=normalizeCountry(derived);
        }
        if(cachedCountry.isEmpty()||"default".equalsIgnoreCase(cachedCountry))cachedCountry="Pays non précisé";
        cached.add(new Channel(name,url,logo,group,cachedVavoo,o.optString("sourceUrl",""),cachedCountry));
      }
      if(!cached.isEmpty()){
        channels.clear();channels.addAll(cached);
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
  private static String countryKey(String value){
    if(value==null)return "";
    String normalized=java.text.Normalizer.normalize(value.trim(),java.text.Normalizer.Form.NFD);
    normalized=normalized.replaceAll("\\p{M}+","").toLowerCase(java.util.Locale.ROOT);
    return normalized.replaceAll("[^a-z0-9]+"," ").trim();
  }
  private static final java.util.Map<String,String> COUNTRY_ALIASES=buildCountryAliases();
  private static java.util.Map<String,String> buildCountryAliases(){
    java.util.HashMap<String,String> map=new java.util.HashMap<>();
    for(String code:java.util.Locale.getISOCountries()){
      java.util.Locale loc=new java.util.Locale("",code);
      String french=loc.getDisplayCountry(java.util.Locale.FRENCH);
      String english=loc.getDisplayCountry(java.util.Locale.ENGLISH);
      if(french==null||french.trim().isEmpty())continue;
      map.put(countryKey(code),french);
      map.put(countryKey(french),french);
      map.put(countryKey(english),french);
    }
    map.put(countryKey("UK"),"Royaume-Uni");
    map.put(countryKey("Great Britain"),"Royaume-Uni");
    map.put(countryKey("USA"),"États-Unis");
    map.put(countryKey("United States of America"),"États-Unis");
    map.put(countryKey("South Korea"),"Corée du Sud");
    map.put(countryKey("Republic of Korea"),"Corée du Sud");
    map.put(countryKey("North Korea"),"Corée du Nord");
    map.put(countryKey("Ivory Coast"),"Côte d’Ivoire");
    map.put(countryKey("Czech Republic"),"Tchéquie");
    map.put(countryKey("Russia"),"Russie");
    map.put(countryKey("Syria"),"Syrie");
    map.put(countryKey("Iran"),"Iran");
    map.put(countryKey("Moldova"),"Moldavie");
    map.put(countryKey("Vietnam"),"Viêt Nam");
    map.put(countryKey("Turkey"),"Turquie");
    map.put(countryKey("Türkiye"),"Turquie");
    map.put(countryKey("Taiwan"),"Taïwan");
    map.put(countryKey("Vatican City"),"Vatican");
    map.put(countryKey("Palestine"),"Palestine");
    map.put(countryKey("Kosovo"),"Kosovo");
    return map;
  }
  private String normalizeCountry(String raw){
    if(raw==null)return "";
    String first=raw.trim().split("[,;]")[0].trim();
    if(first.isEmpty())return "";
    String canonical=COUNTRY_ALIASES.get(countryKey(first));
    return canonical==null?first:canonical;
  }
  private void loadChannels(){
    status.setText("Chargement des catalogues VAVOO + IPTV-ORG…");catalogStatus="Chargement des catalogues VAVOO + IPTV-ORG…";
    io.execute(()->{
      ArrayList<Channel> merged=new ArrayList<>();
      ArrayList<Channel> vavooAll=new ArrayList<>();
      String vavooError="",iptvError="";
      try{
        for(VavooProvider.VavooChannel item:VavooProvider.loadChannels()){
          String vavooCountry=normalizeCountry(item.country);
          if(vavooCountry.isEmpty()||"default".equalsIgnoreCase(vavooCountry))vavooCountry=normalizeCountry(item.group);
          if(vavooCountry.isEmpty()||"default".equalsIgnoreCase(vavooCountry))vavooCountry="Pays non précisé";
          Channel ch=new Channel(item.name,"vavoo://"+item.id,item.logo,
            "VAVOO · "+(item.group==null||item.group.trim().isEmpty()?"Direct":item.group),
            true,item.sourceUrl,vavooCountry.isEmpty()?"Pays non précisé":vavooCountry);
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
            logo="";group="Pays non précisé";country="Pays non précisé";
            int a=line.indexOf("tvg-logo=\"");
            if(a>=0){int z=line.indexOf('"',a+10);if(z>a)logo=line.substring(a+10,z);}
            int g=line.indexOf("group-title=\"");
            if(g>=0){int z=line.indexOf('"',g+13);if(z>g)group=line.substring(g+13,z);}
            // In IPTV-ORG's index.country.m3u the group-title is the broadcast country.
            String groupedCountry=normalizeCountry(group);
            if(!groupedCountry.isEmpty()&&!"Pays non précisé".equalsIgnoreCase(groupedCountry))country=groupedCountry;
            else{country=normalizeCountry(attribute(line,"tvg-country"));if(country.isEmpty())country=normalizeCountry(attribute(line,"country"));}
            if(country.isEmpty())country="Pays non précisé";
          }else if(line.startsWith("http://")||line.startsWith("https://")){
            String url=line.trim();
            merged.add(new Channel(name,url,logo,"IPTV-ORG · "+group,false,null,country));
          }
        }
        reader.close();conn.disconnect();
      }catch(Exception e){iptvError=e.getMessage()==null?"playlist IPTV-ORG indisponible":e.getMessage();}
      final ArrayList<Channel> all=new ArrayList<>(merged);
      final String ve=vavooError,ie=iptvError;
      runOnUiThread(()->{
        channels.clear();channels.addAll(all);
        if(!all.isEmpty())saveCachedChannels(all);
        showChannels(new ArrayList<>(all));
        String msg=all.size()+" chaînes réunies";
        if(!ve.isEmpty())msg+=" · VAVOO indisponible";
        if(!ie.isEmpty())msg+=" · IPTV-ORG indisponible";
        status.setText(msg);catalogStatus=msg+"\nVAVOO : "+(ve.isEmpty()?"catalogue chargé · "+vavooAll.size()+" chaînes":"indisponible · "+ve)+"\nIPTV-ORG : "+(ie.isEmpty()?"playlist internationale chargée":"indisponible · "+ie);
        if(homeWebView!=null&&homeWebReady)homeWebView.evaluateJavascript("setCatalogStatus("+JSONObject.quote(catalogStatus)+");",null);
        if(all.isEmpty())Toast.makeText(this,"Aucun catalogue chargé. VAVOO : "+ve+" · IPTV-ORG : "+ie,Toast.LENGTH_LONG).show();
      });
    });
  }
  private String attribute(String line,String key){int a=line.indexOf(key+"=");if(a<0)return "";int start=a+key.length()+1;if(start<line.length()&&line.charAt(start)=='"')start++;int end=line.indexOf('"',start);return end>start?line.substring(start,end):"";}
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
  public final class HomeBridge {
    @JavascriptInterface public void ready(){runOnUiThread(()->{homeWebReady=true;renderChannelsToWeb();});}
    @JavascriptInterface public void refresh(){runOnUiThread(()->loadChannels());}
    @JavascriptInterface public void setCurrentPage(String page){activeSection=page==null?"Accueil":page;}
    @JavascriptInterface public String getCatalogStatus(){return catalogStatus;}
    @JavascriptInterface public void playChannel(int index){runOnUiThread(()->{if(index>=0&&index<channels.size())play(channels.get(index));});}
    @JavascriptInterface public void toggleFavorite(int index){runOnUiThread(()->{if(index>=0&&index<channels.size()){MainActivity.this.toggleFavorite(channels.get(index));if(homeWebView!=null&&homeWebReady)homeWebView.evaluateJavascript("updateChannelFavorite("+index+","+isFavorite(channels.get(index))+");",null);}});}
  }
  private boolean isFavorite(Channel ch){return getSharedPreferences("favorites",MODE_PRIVATE).getBoolean(ch.url,false);}
  private void toggleFavorite(Channel ch){SharedPreferences p=getSharedPreferences("favorites",MODE_PRIVATE);p.edit().putBoolean(ch.url,!p.getBoolean(ch.url,false)).apply();}
  private FrameLayout playerStage;
  private void play(Channel ch){
    currentChannel=ch;
    if(homeWebView!=null){try{homeWebView.stopLoading();homeWebView.removeJavascriptInterface("NisskenziBridge");homeWebView.destroy();}catch(Exception ignored){}homeWebView=null;homeWebReady=false;}
    if(player!=null){player.release();player=null;}
    root.removeAllViews();root.setPadding(0,0,0,0);root.setOrientation(LinearLayout.VERTICAL);
    LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(18,10,18,10);top.setBackgroundColor(0xFF090B10);
    TextView back=actionButton("‹  ACCUEIL",panel);back.setFocusable(true);back.setOnClickListener(v->returnHome());top.addView(back);
    nowTitle=text(ch.name,17,Color.WHITE,true);nowTitle.setSingleLine(true);nowTitle.setEllipsize(TextUtils.TruncateAt.END);nowTitle.setPadding(14,0,14,0);top.addView(nowTitle,new LinearLayout.LayoutParams(0,-2,1));
    TextView favorite=actionButton(isFavorite(ch)?"★":"☆",isFavorite(ch)?0xFF775A12:panel);LinearLayout.LayoutParams favP=new LinearLayout.LayoutParams(-2,-2);favP.leftMargin=8;top.addView(favorite,favP);favorite.setOnClickListener(v->{toggleFavorite(ch);favorite.setText(isFavorite(ch)?"★":"☆");favorite.setBackground(rounded(isFavorite(ch)?0xFF775A12:panel,10));});
    root.addView(top,new LinearLayout.LayoutParams(-1,-2));
    playerStage=new FrameLayout(this);playerStage.setBackgroundColor(Color.BLACK);
    playerView=new PlayerView(this);playerView.setUseController(true);playerView.setKeepContentOnPlayerReset(true);playerView.setShutterBackgroundColor(Color.BLACK);
    playerStage.addView(playerView,new FrameLayout.LayoutParams(-1,-1));
    root.addView(playerStage,new LinearLayout.LayoutParams(-1,0,1));
    LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER_VERTICAL);controls.setPadding(18,8,18,8);controls.setBackgroundColor(0xFF090B10);
    TextView live=text("●  EN DIRECT",11,0xFFFF4D5A,true);controls.addView(live,new LinearLayout.LayoutParams(0,-2,1));
    TextView retry=actionButton("↻ RELANCER",panel);retry.setOnClickListener(v->startPlayback(ch));controls.addView(retry);
    TextView info=actionButton("ⓘ",panel);LinearLayout.LayoutParams infoP=new LinearLayout.LayoutParams(-2,-2);infoP.leftMargin=8;controls.addView(info,infoP);info.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(ch.name).setMessage("Groupe : "+ch.group+"\n\nSource : "+(ch.isVavoo?"VAVOO":"flux externe")+"\n\nLa disponibilité dépend de la source et de ses conditions d’accès.").setPositiveButton("OK",null).show());
    root.addView(controls,new LinearLayout.LayoutParams(-1,-2));
    startPlayback(ch);
  }
  private void startPlayback(Channel ch){
    if(player!=null){player.release();player=null;}
    DefaultHttpDataSource.Factory httpDataSourceFactory=new DefaultHttpDataSource.Factory()
      .setUserAgent(ch.isVavoo?"VAVOO/2.6":"NISSKENZI+/1.7")
      .setAllowCrossProtocolRedirects(true);
    DefaultDataSource.Factory dataSourceFactory=new DefaultDataSource.Factory(this,httpDataSourceFactory);
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
  private void returnHome(){if(player!=null){player.release();player=null;}playerStage=null;currentChannel=null;buildHome();showChannels(new ArrayList<>(channels));}
  @Override public boolean dispatchKeyEvent(android.view.KeyEvent event){return super.dispatchKeyEvent(event);}
  @Override public void onBackPressed(){if(player!=null){returnHome();}else if(homeWebView!=null&&homeWebReady&&!"Live TV".equals(activeSection)){homeWebView.evaluateJavascript("setPage('Live TV')",null);activeSection="Live TV";}else super.onBackPressed();}
  @Override protected void onDestroy(){if(player!=null)player.release();if(homeWebView!=null){try{homeWebView.removeJavascriptInterface("NisskenziBridge");homeWebView.destroy();}catch(Exception ignored){}}io.shutdownNow();super.onDestroy();}
}
