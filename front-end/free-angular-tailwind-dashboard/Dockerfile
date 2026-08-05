# ============================================
# Étape 1 : compilation de l'application Angular
# ============================================
FROM node:22-alpine AS build

WORKDIR /app

# Copier d'abord les fichiers npm pour profiter du cache Docker
COPY package.json package-lock.json ./

# Installation reproductible des dépendances
RUN npm ci

# Copier tout le projet Angular
COPY . .

# Compiler l'application en production
RUN npm run build -- --configuration production

# Vérifier que le build Angular existe
RUN test -d /app/dist/ng-tailadmin/browser \
    && test -f /app/dist/ng-tailadmin/browser/index.html


# ============================================
# Étape 2 : serveur Nginx
# ============================================
FROM nginx:alpine

# Nettoyer les fichiers Nginx par défaut
RUN rm -rf /usr/share/nginx/html/*

# Copier le nouveau build Angular
COPY --from=build \
    /app/dist/ng-tailadmin/browser \
    /usr/share/nginx/html

# Copier la configuration Nginx
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]