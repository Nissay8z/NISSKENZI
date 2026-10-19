# NISSKENZI

Catalogue multimédia personnel actualisé automatiquement.

## Playlists

Après une exécution réussie du workflow, les listes sont accessibles ici :

- Catalogue mondial : [playlist.m3u](https://raw.githubusercontent.com/Nissay8z/NISSKENZI/main/playlist.m3u)
- Catalogue France : [playlist-fr.m3u](https://raw.githubusercontent.com/Nissay8z/NISSKENZI/main/playlist-fr.m3u)

Le catalogue mondial est combiné depuis des listes communautaires; le catalogue français est généré séparément. Les doublons d'URL exacts sont retirés et les métadonnées de chaîne sont conservées.

## Guide des programmes (EPG)

Le fichier mondial intègre plusieurs guides XMLTV de la communauté. Pour le catalogue français, l'en-tête M3U référence le guide :

`https://iptv-org.github.io/epg/guides/fr.xml`

La disponibilité des données de guide dépend de la chaîne et de la source.

## Actualisation automatique

Le workflow GitHub Actions :

- se lance manuellement depuis **Actions** ;
- se lance environ toutes les six heures ;
- teste le programme avant de générer les fichiers ;
- n'enregistre les playlists dans Git que si leur contenu a changé ;
- utilise une tentative de rebase avant l'envoi pour limiter les conflits si le dépôt a changé pendant l'exécution.

Le PC n'a pas besoin de rester allumé. GitHub peut retarder des exécutions planifiées.

## Sources et limites

Les URLs sources sont configurées dans `sources.txt` et `sources-fr.txt`. N'ajoute que des sources et des flux que tu es autorisé à utiliser. Une chaîne listée peut être hors service ou géobloquée; une présence dans un catalogue public ne garantit pas le bon fonctionnement du flux ni un droit de rediffusion.

NISSKENZI génère des fichiers à partir des listes configurées. Il ne maintient pas un proxy vidéo permanent et ne contourne pas les contrôles d'accès.

## Exécution locale (facultative)

Python 3.12 ou plus récent :

```bash
python -m unittest discover -s tests -v
python refresh_catalogue.py --sources sources.txt --output playlist.m3u
python refresh_catalogue.py --sources sources-fr.txt --output playlist-fr.m3u --epg-url https://iptv-org.github.io/epg/guides/fr.xml
```
