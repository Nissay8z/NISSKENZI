# NissX — espace vidéo adulte

NissX est un site personnel destiné exclusivement aux adultes (18+), conçu pour GitHub Pages et les téléphones.

## Fonctionnalités actuelles
- Interface sombre responsive, avec identité NissX.
- Écran de confirmation 18+ (simple confirmation locale, pas une vérification d'âge fiable).
- Lecteur HTML5 acceptant les URL directes HTTP/HTTPS vers des vidéos.
- Aucun compte utilisateur et aucun hébergement de fichiers vidéo par le site.

## Limites du lecteur
Le lecteur ne peut pas lire automatiquement toutes les pages de plateformes. Une source peut bloquer la lecture externe (CORS, hotlink protection, connexion, jetons d'accès ou formats non pris en charge). Le site ne contourne pas les DRM, les paywalls ou les contrôles d'accès. Utilise uniquement des vidéos légales, consensuelles, impliquant des adultes et que tu as le droit de regarder ou de diffuser.

## Déploiement GitHub Pages
Le workflow GitHub Actions publie le site depuis la branche `main`. Dans **Settings → Pages**, vérifie que la source est configurée sur **GitHub Actions**.

URL du site :
https://nissay8z.github.io/NISSKENZI/

## Catalogue
Le catalogue vidéo n'est pas encore connecté à une source. Les prochaines entrées devront provenir de sources légales et autorisées. NissX n'héberge ni ne scrape automatiquement des vidéos de sites tiers.

## Outils conservés dans le dépôt
Des pages utilitaires peuvent encore exister dans des sous-dossiers, dont le convertisseur :
https://nissay8z.github.io/NISSKENZI/convertisseur-lien-v2/

## Développement
Le site principal est un fichier HTML sans framework : modifie `index.html`, puis pousse sur `main`.
