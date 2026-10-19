# NISSKENZI

Outil personnel pour créer et consulter un catalogue de chaînes en direct.

## Ouvrir NISSKENZI Live

L'application du lecteur se trouve dans [nisskenzi-live.html](nisskenzi-live.html).

Pour l'ouvrir comme une vraie page web dans VYPN, active GitHub Pages une seule fois :

1. Va dans **Settings → Pages** du dépôt.
2. Dans **Build and deployment → Source**, choisis **GitHub Actions**.
3. Va dans **Actions → Deploy NISSKENZI Live**, puis ouvre la dernière exécution.
4. Une fois le déploiement réussi, ouvre **https://nissay8z.github.io/NISSKENZI/** dans VYPN.

Le workflow publie uniquement la page du lecteur sous le nom `index.html`. Quand `nisskenzi-live.html` change sur la branche `main`, il redéploie la page. Le lecteur contient une recherche, les catégories, les favoris, la sélection de playlists et un lecteur vidéo. Il utilise `https://mhub.mx/mhub.js` lorsque le navigateur hôte expose l'API mHub, sinon tente le mode navigateur classique.

## Playlists disponibles actuellement

- [Playlist mondiale](https://raw.githubusercontent.com/Nissay8z/NISSKENZI/main/playlist.m3u)
- [Playlist France](https://raw.githubusercontent.com/Nissay8z/NISSKENZI/main/playlist-fr.m3u)
- [Rapport du catalogue mondial](catalogue-status.json)
- [Rapport du catalogue France](catalogue-status-fr.json)

**État de l'intégration Vavoo : elle n'est pas encore intégrée.** Le lecteur utilise actuellement les playlists existantes; la présence du script mHub ne découvre pas à elle seule le catalogue Vavoo.

## Actualisation du catalogue

Le workflow **NISSKENZI catalogue refresh** s'exécute environ toutes les six heures et peut être lancé manuellement. Les catalogues communautaires configurés dans `sources.txt` et `sources-fr.txt` restent les sources de secours.

Une source M3U principale peut être fournie avec le secret GitHub Actions `NISSKENZI_PRIMARY_M3U_URL`. Ne place pas d'URL privée ou de jeton dans un fichier public du dépôt.

## Vérification des flux

Le workflow manuel **NISSKENZI stream health sample** effectue un diagnostic limité sur un échantillon de chaînes. Une réponse HTTP réussie ne garantit pas que la vidéo fonctionne dans tous les lecteurs.

## Confidentialité

Le dépôt est public. Les playlists générées et les URL directes qu'elles contiennent sont donc lisibles publiquement. N'y ajoute aucune URL privée ni aucun jeton.

## Tests locaux

Python 3.12+ :

```bash
python -m unittest discover -s tests -v
```
