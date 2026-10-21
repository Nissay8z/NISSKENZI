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