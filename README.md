# NISSKENZI — Anime

Catalogue anime personnel, gratuit et léger, conçu pour **GitHub Pages** et les téléphones.

## Fonctionnalités
- Catalogue populaire et animes de la saison.
- Recherche avec suggestions de résultats via l'API Jikan.
- Fiches détaillées : synopsis, genres, score, nombre d'épisodes et année.
- Liste personnelle enregistrée dans le navigateur (localStorage).
- Affichage adaptatif mobile/ordinateur, images chargées à la demande.
- Aucun compte, aucune clé API, aucun serveur à maintenir.

## Déploiement GitHub Pages
Le workflow GitHub Actions publie automatiquement le site depuis la branche `main` à chaque mise à jour. Dans **Settings → Pages**, choisis **GitHub Actions** comme source si ce n'est pas déjà fait.

Quand le déploiement est terminé, l'adresse du projet sera normalement :
https://nissay8z.github.io/NISSKENZI/

## API anime
Le site utilise [Jikan](https://jikan.moe/), une API REST gratuite et open source pour les données publiques de MyAnimeList. Elle ne demande pas de clé API, mais applique des limites de requêtes : si elle est temporairement indisponible, réessaie un peu plus tard. Consulte sa documentation et ses conditions avant tout usage important.

## À propos du visionnage
Le site est un catalogue : il ne télécharge, ne stocke et n'héberge aucune vidéo. Les liens de visionnage doivent pointer vers des services officiels ou des sources dont tu as l'autorisation d'utiliser le contenu. Un scraper conçu pour un environnement React Native ne fonctionne pas forcément dans un site statique : les navigateurs bloquent souvent les requêtes cross-origin (CORS), et les en-têtes personnalisés comme User-Agent/Referer ne peuvent généralement pas être imposés par JavaScript côté client. Pour une intégration fiable, utilise une API documentée et autorisée ou un backend que tu contrôles et qui respecte les droits des sources.

## Développement
Le site est volontairement sans framework et sans étape de compilation : modifie `index.html`, puis pousse sur `main`.

## Application Android (APK)

Le dépôt contient une configuration Capacitor et un workflow GitHub Actions qui construit automatiquement un APK de test installable, à chaque modification des fichiers de l'application.

1. Ouvre l'onglet [Actions](https://github.com/Nissay8z/NISSKENZI/actions/workflows/android-apk.yml).
2. Sélectionne **Build NISSKENZI Android APK**, puis le dernier lancement terminé.
3. Dans **Artifacts**, télécharge **NISSKENZI-Android-APK** et extrais le fichier `app-debug.apk`.
4. Ouvre l'APK sur ton téléphone et autorise l'installation depuis cette source si Android le demande.

L'application vise une large compatibilité Android (Capacitor 6, Android 5.1/API 22 minimum par défaut). Le Galaxy A51 est largement compatible. Il s'agit d'un APK debug pour installation personnelle, pas d'une version signée pour publication sur le Play Store.

Le catalogue utilise une API en ligne : l'application nécessite donc une connexion Internet pour récupérer les fiches et les recherches. Les résultats mis en cache peuvent rester disponibles temporairement.
