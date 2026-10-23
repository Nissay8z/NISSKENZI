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
  private LinearLayout root, list; private TextView status, nowTitle; private EditText searchBox; private PlayerView playerView; private ExoPlayer player; private Channel currentChannel; private String activeFilter="TOUT"; private String searchQuery="";
  private final ArrayList<Channel> channels=new ArrayList<>(); private final ArrayList<Channel> allVavooChannels=new ArrayList<>(); private final ExecutorService io=Executors.newSingleThreadExecutor(); private final ExecutorService logoIo=Executors.newFixedThreadPool(3);
  private static class Channel { String name,url,logo,group,vavooSourceUrl,vavooCountry; boolean isVavoo; Channel(String n,String u,String l,String g){this(n,u,l,g,false,null,"");} Channel(String n,String u,String l,String g,boolean v,String source){this(n,u,l,g,v,source,"");} Channel(String n,String u,String l,String g,boolean v,String source,String country){name=n;url=u;logo=l;group=g;isVavoo=v;vavooSourceUrl=source;vavooCountry=country;} }
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
    TextView brand=text("N",28,Color.WHITE,true);brand.setGravity(Gravity.CENTER);brand.setBackground(rounded(0xFF182A54,14));brand.setTextColor(0xFF55D9FF);header.addView(brand,new LinearLayout.LayoutParams(44,44));
    LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);titles.setPadding(12,0,0,0);
    titles.addView(text("NISSKENZI+",20,Color.WHITE,true));titles.addView(text("TOUT LE DIVERTISSEMENT. EN UNE SEULE APP.",8,0xFFADB4C2,true));
    header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
    TextView importBtn=actionButton("＋ SOURCES",panel);importBtn.setOnClickListener(v->openSourceMenu());header.addView(importBtn); TextView direct=actionButton("DIRECT",red);direct.setOnClickListener(v->loadChannels());header.addView(direct);
    TextView refresh=actionButton("↻",panel);LinearLayout.LayoutParams refreshP=new LinearLayout.LayoutParams(48,48);refreshP.leftMargin=8;header.addView(refresh,refreshP);refresh.setOnClickListener(v->loadChannels());root.addView(header);
    searchBox=new EditText(this);searchBox.setSingleLine(true);searchBox.setTextSize(14);searchBox.setTextColor(Color.WHITE);searchBox.setHintTextColor(0xFF8993A4);searchBox.setHint("⌕   Rechercher une chaîne…");searchBox.setPadding(18,4,18,4);searchBox.setBackground(rounded(0xFF171B24,14));searchBox.setText(searchQuery);LinearLayout.LayoutParams searchP=new LinearLayout.LayoutParams(-1,50);searchP.topMargin=16;root.addView(searchBox,searchP);searchBox.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){searchQuery=s.toString();showChannels(null);}public void afterTextChanged(android.text.Editable e){}});
    LinearLayout hero=new LinearLayout(this);hero.setOrientation(LinearLayout.VERTICAL);hero.setPadding(22,20,22,20);android.graphics.drawable.GradientDrawable heroBg=new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,new int[]{0xFF31394B,0xFF171B25,0xFF40161D});heroBg.setCornerRadius(22);hero.setBackground(heroBg);
    LinearLayout.LayoutParams heroP=new LinearLayout.LayoutParams(-1,-2);heroP.topMargin=18;heroP.bottomMargin=10;root.addView(hero,heroP);
    TextView eyebrow=text("VAVOO  ×  IPTV-ORG  •  MULTI-SOURCES",10,0xFF55D9FF,true);hero.addView(eyebrow);
    TextView title=text("Tout le direct.\nAu même endroit.",30,Color.WHITE,true);LinearLayout.LayoutParams titleP=new LinearLayout.LayoutParams(-1,-2);titleP.topMargin=6;hero.addView(title,titleP);
    TextView desc=text("Live TV • Films • Séries • Animés • Sport • Favoris",13,0xFFD2D7E0,false);LinearLayout.LayoutParams descP=new LinearLayout.LayoutParams(-1,-2);descP.topMargin=6;hero.addView(desc,descP);
    status=text("Chargement des catalogues VAVOO + IPTV-ORG…",12,0xFFADB4C2,false);LinearLayout.LayoutParams statusP=new LinearLayout.LayoutParams(-1,-2);statusP.topMargin=12;hero.addView(status,statusP);
    LinearLayout filters=new LinearLayout(this);filters.setOrientation(LinearLayout.HORIZONTAL);filters.setPadding(0,6,0,4);for(String f:new String[]{"TOUT","FAVORIS"}){TextView chip=actionButton(f,f.equals(activeFilter)?red:panel);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);cp.rightMargin=8;filters.addView(chip,cp);chip.setOnClickListener(v->{activeFilter=f;showChannels(null);});}root.addView(filters);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(0,8,0,20);scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));list=content;
    TextView foot=text("Flux publics · La disponibilité dépend des diffuseurs",10,0xFF717B8D,false);foot.setGravity(Gravity.CENTER);root.addView(foot);
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
        String line,name="Chaîne",logo="",group="Pays non précisé";
        while((line=reader.readLine())!=null){
          if(line.startsWith("#EXTINF:")){
            int comma=line.lastIndexOf(',');
            name=comma>=0?line.substring(comma+1).trim():"Chaîne";
            logo="";group="Pays non précisé";
            int a=line.indexOf("tvg-logo=\"");
            if(a>=0){int z=line.indexOf('"',a+10);if(z>a)logo=line.substring(a+10,z);}
            int g=line.indexOf("group-title=\"");
            if(g>=0){int z=line.indexOf('"',g+13);if(z>g)group=line.substring(g+13,z);}
          }else if(line.startsWith("http://")||line.startsWith("https://")){
            String url=line.trim();
            merged.add(new Channel(name,url,logo,"IPTV-ORG · "+group,false,null,group));
          }
        }
        reader.close();conn.disconnect();
      }catch(Exception e){iptvError=e.getMessage()==null?"playlist IPTV-ORG indisponible":e.getMessage();}
      final ArrayList<Channel> all=new ArrayList<>(merged);
      final ArrayList<Channel> vAll=new ArrayList<>(vavooAll);
      final String ve=vavooError,ie=iptvError;
      runOnUiThread(()->{
        allVavooChannels.clear();allVavooChannels.addAll(all);
        channels.clear();channels.addAll(all);
        activeFilter="TOUT";
        showChannels(new ArrayList<>(all));
        String msg=all.size()+" chaînes réunies";
        if(!ve.isEmpty())msg+=" · VAVOO indisponible";
        if(!ie.isEmpty())msg+=" · IPTV-ORG indisponible";
        status.setText(msg);
        if(all.isEmpty())Toast.makeText(this,"Aucun catalogue chargé. VAVOO : "+ve+" · IPTV-ORG : "+ie,Toast.LENGTH_LONG).show();
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
  private void loadLogo(ImageView target,TextView fallback,String address){logoIo.execute(()->{try{HttpURLConnection conn=(HttpURLConnection)new URL(address).openConnection();conn.setConnectTimeout(5000);conn.setReadTimeout(7000);conn.setInstanceFollowRedirects(true);conn.setRequestProperty("User-Agent","NissayStream/1.4");android.graphics.Bitmap bmp=BitmapFactory.decodeStream(conn.getInputStream());conn.disconnect();if(bmp!=null)runOnUiThread(()->{target.setImageBitmap(bmp);fallback.setVisibility(View.GONE);});}catch(Exception ignored){}});}
  private boolean isFavorite(Channel ch){return getSharedPreferences("favorites",MODE_PRIVATE).getBoolean(ch.url,false);}
  private void toggleFavorite(Channel ch){SharedPreferences p=getSharedPreferences("favorites",MODE_PRIVATE);p.edit().putBoolean(ch.url,!p.getBoolean(ch.url,false)).apply();}
  private FrameLayout playerStage;
  private LinearLayout channelDrawer;
  private boolean countryDrawerOpen=false;
  private void play(Channel ch){
    currentChannel=ch;
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
    TextView live=text("●  EN DIRECT",11,0xFFFF4D5A,true);controls.addView(live,new LinearLayout.LayoutParams(0,-2,1));
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
  @Override protected void onDestroy(){if(player!=null)player.release();io.shutdownNow();logoIo.shutdownNow();super.onDestroy();}
}
