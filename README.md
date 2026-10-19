# NISSKENZI Ciné

Un catalogue façon Netflix pour regarder des films hébergés par Internet Archive et dont les métadonnées indiquent une licence du domaine public ou Creative Commons compatible.

## Ce projet fait quoi ?

- Recherche dans le catalogue par titre, créateur ou sujet.
- Affiche les affiches, descriptions, années et indications de licence disponibles.
- Lit directement les fichiers MP4 d'Internet Archive dans le lecteur intégré.
- Offre des sections populaires et nouveautés.
- Actualise le catalogue chaque jour et redéploie le site via GitHub Actions.
- Ne réhéberge pas de vidéo : la lecture est servie par Internet Archive.

Il ne collecte pas les liens de lecture depuis Recherche-Stream ni depuis des sites tiers dont les autorisations ne sont pas établies. Il ne contourne pas de paywalls, protections d'accès ou restrictions techniques.

## Ouvrir le site

Adresse prévue : https://nissay8z.github.io/NISSKENZI/

Le workflow tente de publier le site avec GitHub Pages. Si le déploiement affiche que Pages n'est pas activé, ouvre **Settings → Pages** dans ce dépôt, sélectionne **GitHub Actions** comme source de publication, puis relance **Actions → Refresh NISSKENZI Cine and deploy**.

## Catalogue et licences

Le constructeur utilise les API publiques de recherche et de métadonnées d'Internet Archive. Il ne conserve que les éléments avec une licence déclarée dans une de ces familles : Public Domain Mark, CC0, CC BY ou CC BY-SA, et pour lesquels l'API fournit un MP4 public. La page de chaque œuvre mène à sa fiche d'origine et à la licence.

Les métadonnées de licence sont fournies par les déposants ; ce n'est pas une garantie juridique indépendante. Vérifie la fiche et le statut des droits dans ton pays. Les œuvres peuvent être retirées, indisponibles, ou avoir des métadonnées incomplètes.

## Actualisation

- Automatique chaque jour à 04:17 UTC.
- Déclenchable manuellement dans **Actions → Refresh NISSKENZI Cine and deploy → Run workflow**.
- Si l'API échoue ou retourne trop peu de résultats validés, le script échoue sans écraser le dernier catalogue fonctionnel.

## Développement local

Python 3.12+, aucune dépendance tierce :

\`\`\`bash
python -m unittest discover -s tests -v
python catalog_builder.py
\`\`\`

## Données

Le fichier \`catalog.json\` est généré automatiquement à partir des API publiques d'Internet Archive. Les liens vers vidéos et images pointent vers archive.org et ne sont pas copiés sur GitHub.
