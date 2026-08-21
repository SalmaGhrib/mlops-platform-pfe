# SMART MODEL GATEWAY — Livraison sécurisée des clés API

## 1. Vue d'ensemble

Le **Smart Model Gateway (SMG)** expose des modèles déployés à des applications externes
via une API publique sécurisée par clé (`X-API-KEY`).

Depuis le **02/08/2026**, une clé n'est plus affichée « en clair » dans l'UI pour être copiée.
Elle est désormais **livrée par email via un lien sécurisé à usage unique** :

- lien **valable 24h**,
- **usage unique** (une fois la clé révélée, le lien est invalidé : second clic → `410 LINK_USED`),
- le destinataire **n'a pas besoin de compte** sur la plateforme (le token du lien est la preuve),
- le lien ne révèle **qu'une seule clé**,
- la clé reste soumise à son **quota**, sa **date d'expiration** et sa **révocation** après livraison.

### Fallback
Si **aucun email** n'est renseigné à la création, la clé est affichée **une seule fois** au propriétaire
(comportement antérieur conservé).

---

## 2. Parcours utilisateur (propriétaire du modèle)

1. Ouvrir **Model Registry** → déploiement actif → panneau **SMART MODEL GATEWAY** → **Gérer les clés API**.
2. Renseigner (optionnel) le **nom**, le **quota journalier**, la **date d'expiration**, puis l'**email du destinataire**.
3. Cliquer **Générer la clé**.
   - Avec email → message *« lien sécurisé à usage unique (valable 24h) envoyé à … »* ; **aucune clé affichée**.
   - Sans email → la clé complète est affichée **une seule fois** (copie immédiate).
4. Le destinataire clique le lien reçu par email → page publique **`/deliver-api-key`** → la clé est **révélée une seule fois** avec bouton **Copier**.
5. Un second clic sur le lien affiche *« Ce lien a déjà été utilisé »* (`410 LINK_USED`).
6. **Renvoyer le lien** (bouton dans la liste des clés) : génère un nouveau lien, **invalide les précédents**, expire dans 24h.
7. Les clés restent gérées comme avant : activation/désactivation, révocation, statistiques d'usage.

### Modèle privé
- Si le modèle est **PRIVATE**, la création de clé est **désactivée dans l'UI** et refusée par l'API
  (`403 EXTERNAL_ACCESS_DISABLED`). Il faut passer le modèle en **PUBLIC_API** ou **SHARED**.
- Modes d'accès :
  - **PUBLIC_API** : une clé valide suffit à l'appel.
  - **SHARED** : clé valide **+** header `X-USER-EMAIL` (email autorisé dans la liste de partage). À la création, l'email du destinataire doit être dans la liste de partage.
  - **PRIVATE** : aucun accès externe, même avec une clé.

---

## 3. Intégration côté client (application externe)

### 3.1 Endpoint

```
POST http://128.251.112.197/api/external/models/{registeredModelName}/predict
POST http://128.251.112.197/api/external/models/{registeredModelName}/predict-image
POST http://128.251.112.197/api/external/models/{registeredModelName}/predict-text
```

| Header | Obligatoire | Description |
|---|---|---|
| `X-API-KEY` | Oui | Clé livrée par le lien sécurisé |
| `X-USER-EMAIL` | Si modèle **SHARED** | Email du client autorisé (liste de partage) |
| `Content-Type` | Oui | `application/json` |

> **Le client ne fournit JAMAIS `model_name` / `model_alias` dans le body.**
> Le modèle est identifié par l'URL (`{registeredModelName}`). Le Gateway résout la
> version active côté backend et injecte automatiquement `model_name` + `model_alias="production"`
> avant d'appeler le model server, sans écraser les features métier. Toute tentative
> d'envoyer ces champs est ignorée/écrasée par les valeurs autoritatives du backend.

Formats de body supportés :

| Type de modèle | Body |
|---|---|
| TABULAR / TEXT | `{ "rows": [ { "feature_1": 1.2, "feature_2": 0.4 } ] }` ou `{ "inputs": [...] }` |
| IMAGE | `{ "imageBase64": "<base64>" }` |

### 3.2 Exemple cURL

```bash
curl -X POST \
  "http://128.251.112.197/api/external/models/couleurrr_model/predict" \
  -H "Content-Type: application/json" \
  -H "X-API-KEY: mlops_live_<ta_cle_api>" \
  -d '{ "imageBase64": "<image_en_base64>" }'
```

Modèle **SHARED** :

```bash
curl -X POST \
  "http://128.251.112.197/api/external/models/mon_modele/predict" \
  -H "Content-Type: application/json" \
  -H "X-API-KEY: mlops_live_<ta_cle_api>" \
  -H "X-USER-EMAIL: client@example.com" \
  -d '{ "rows": [ { "feature_1": 1.2 } ] }'
```

### 3.3 Exemple Python

```python
import requests

BASE = "http://128.251.112.197"
MODEL = "couleurrr_model"
API_KEY = "mlops_live_<ta_cle_api>"   # clé livrée par email

resp = requests.post(
    f"{BASE}/api/external/models/{MODEL}/predict",
    headers={
        "Content-Type": "application/json",
        "X-API-KEY": API_KEY,
        # "X-USER-EMAIL": "client@example.com",  # obligatoire en mode SHARED
    },
    json={"imageBase64": "<image_en_base64>"},
    timeout=120,
)

print(resp.status_code, resp.json())
```

### 3.4 Limites (headers de réponse)

Chaque réponse inclut :
- `X-RateLimit-Limit` — quota journalier de la clé,
- `X-RateLimit-Remaining` — appels restants aujourd'hui,
- `X-RateLimit-Reset` — fin de la fenêtre.

---

## 4. Contrat d'erreurs

| HTTP | Code | Cas |
|---|---|---|
| 400 | `MODEL_NAME_REQUIRED` / `EMPTY_REQUEST_BODY` / `IMAGE_REQUIRED` | requête invalide |
| 401 | `MISSING_API_KEY` / `INVALID_API_KEY` | clé absente ou invalide |
| 401 | `USER_EMAIL_REQUIRED` | modèle SHARED sans `X-USER-EMAIL` |
| 403 | `KEY_DISABLED` | clé désactivée |
| 403 | `KEY_NOT_FOR_MODEL` | clé valide mais pas pour ce modèle |
| 403 | `EXTERNAL_ACCESS_DISABLED` | modèle PRIVATE (création de clé refusée / accès refusé) |
| 403 | `USER_NOT_AUTHORIZED` | email non autorisé pour un modèle SHARED |
| 404 | `MODEL_NOT_FOUND` / `API_KEY_NOT_FOUND` / `LINK_NOT_FOUND` | ressource introuvable |
| 410 | `KEY_REVOKED` | clé révoquée |
| 410 | `KEY_EXPIRED` | clé expirée |
| 410 | `LINK_USED` | lien de livraison déjà utilisé |
| 410 | `LINK_EXPIRED` | lien de livraison expiré (> 24h) |
| 429 | `QUOTA_EXCEEDED` | quota journalier atteint |
| 503 | `MODEL_NOT_DEPLOYED` | aucun déploiement actif prêt |

---

## 5. Modèle de sécurité

- En base, une clé n'est stockée **jamais en clair** : `key_prefix` (recherche) + `key_hash` (SHA-256 du secret).
- Le lien de livraison stocke le token **haché** (`token_hash`) et le secret complet **chiffré** (AES-256-GCM,
  clé dérivée de `APP_GATEWAY_DELIVERY_ENCRYPTION_KEY`, conservée dans le secret Kubernetes `backend-secret`).
- Le token du lien n'est connu que du destinataire de l'email ; il est la seule preuve d'accès.
- Révocation d'une clé → tous ses liens de livraison non utilisés sont invalidés.
- Renvoi de lien → les liens précédents non utilisés sont invalidés (un seul lien valide à la fois).
- Aucun log ne contient la clé complète ni le token brut.
