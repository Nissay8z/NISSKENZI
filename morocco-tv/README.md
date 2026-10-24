# NISSKENZI TV

Application Android TV pour les chaînes marocaines avec une source VAVOO Live configurable.

## Chaînes marocaines
- La liste communautaire IPTV-org est rechargée à l'ouverture / actualisation.
- Des liens publics de diffuseurs sont ajoutés en complément ; leur disponibilité peut changer.

## Ajouter VAVOO Live
La rubrique **VAVOO LIVE** de l'application lit le catalogue d'un proxy que tu héberges et contrôles. Elle ne contient pas de liens VAVOO figés : elle demande `/channels.m3u8` au serveur, puis lit les URLs de flux renvoyées.

Proxy communautaire compatible : https://github.com/Haehnchen/vavoo-iptv-stream-proxy

### Lancer le proxy
1. Sur un PC, mini-PC, Raspberry Pi ou appareil Android avec un environnement Node.js compatible, installe Node.js **24 ou plus récent**.
2. Clone le projet du proxy et installe ses dépendances avec `npm install`.
3. Démarre-le en indiquant une adresse réseau joignable depuis la TV. Exemple, si le serveur est sur `192.168.1.20` :

   ```sh
   node index.js --http-host 192.168.1.20 --http-port 8888 --vavoo-language en --vavoo-region US --vavoo-url-list both
   ```

   Consulte le README du projet proxy pour ses options et les changements éventuels de l'API amont. L'adresse IP doit être celle du serveur sur ton réseau ; elle ne doit pas être `127.0.0.1` si le serveur tourne sur un autre appareil que la TV.
4. Sur l'APK, sélectionne **VAVOO LIVE** et entre l'adresse de base du serveur, par exemple `http://192.168.1.20:8888`. L'application ajoute automatiquement `/channels.m3u8`.
5. Pour que la lecture fonctionne, les URLs de flux générées par le proxy doivent aussi être accessibles depuis la TV. Garde le serveur en marche pendant le visionnage.

Le catalogue et les flux dépendent de la disponibilité du service amont, des règles applicables et de la connectivité. Cette intégration ne contourne pas les DRM ni les contrôles d'accès payants. L'application n'embarque pas le proxy : il faut le faire tourner sur un appareil/serveur séparé.

## Fonctionnalités intelligentes
- **Dossiers de chaînes multi-sources & déduplication** : regroupe automatiquement les chaînes en double et variantes (4K, UHD, FHD, HD, SD, serveurs miroirs `.b`, `.c`, `.s`) sous une entrée unique et épurée.
- **Sélection automatique de la meilleure qualité** : classe et privilégie d'office la meilleure source disponible (4K > FHD > HD > SD).
- **Basculement automatique intelligent (Smart Failover)** : si un flux tombe en panne ou est inaccessible, l'application bascule automatiquement sur la source alternative suivante dans le dossier sans interrompre l'expérience.
- **Sélecteur de flux interactif** : boutons de sélection rapide dans le panneau d'aperçu et commande directe en plein écran (OSD) pour naviguer entre les différents flux.
- **Badge de qualité & dossier** : badges visuels `[4K]`, `[FHD]`, `[HD]` et `[📁 X flux]` dans la liste et le tiroir latéral.

## Construire l'APK
Le workflow GitHub Actions `.github/workflows/build-morocco-tv.yml` compile l'application en debug APK lors d'une modification du projet ou depuis l'onglet **Actions** avec le lancement manuel. L'APK est publiée comme artefact du workflow.
