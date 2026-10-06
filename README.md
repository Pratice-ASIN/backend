# Paiement du timbre fiscal par mobile money

Service de paiement fiable (Spring Boot 3, Java 17) : un usager enregistre une demande
d'acte, le service calcule le montant, lance le débit chez l'opérateur (MTN, MOOV,
Celtiis) puis prend en compte le résultat signé que l'opérateur renvoie plus tard.

La première version avait produit trois incidents en production. Chacun a une
parade explicite :

| Incident | Cause probable | Parade dans ce service |
|---|---|---|
| **Doubles débits** | Double envoi (réseau mobile instable), requêtes simultanées, débit demandé avant d'avoir verrouillé la demande | Contrainte **UNIQUE en base** (un seul paiement en cours ou réussi par demande), clé d'idempotence, appel à l'opérateur **après** commit et par le seul gagnant |
| **Montants erronés** | Montant envoyé par l'application cliente | Montant **calculé par le service** et figé ; tout champ `amount` envoyé est rejeté ; montant du rappel contrôlé |
| **Paiements "réussis" refusés par l'opérateur** | Succès supposé à l'accusé de réception, rappels non authentifiés | Accusé ≠ résultat ; seul un rappel **signé HMAC** ou une consultation de l'opérateur finalise ; un état final ne change plus |

---

## Lancer

Prérequis : JDK 17+ et Maven 3.9+.

```bash
mvn test              # tests unitaires + tests de bout en bout
mvn spring-boot:run   # API sur http://localhost:8080 (base H2 en mémoire)
```

Avec PostgreSQL : `mvn spring-boot:run -Dspring-boot.run.profiles=postgres`
(variables `DB_URL`, `DB_USER`, `DB_PASSWORD`).

Avec Docker (application + PostgreSQL 16, seul Docker est requis) :

```bash
make up      # construit l'image et démarre la pile sur http://localhost:8080
make logs    # logs de l'application
make db      # shell psql sur la base
make down    # arrêt (données conservées) ; `make clean` supprime aussi le volume
```

`make help` liste toutes les commandes.

Documentation interactive (Swagger UI) : <http://localhost:8080/swagger-ui.html>
(spécification OpenAPI : <http://localhost:8080/v3/api-docs>). Renseigner `X-User-Id`
une fois via le bouton **Authorize**.

Les secrets partagés avec les opérateurs se surchargent par variables
d'environnement : `SECRET_MTN`, `SECRET_MOOV`, `SECRET_CELTIIS`.

---

## Démonstration (curl)

L'usager est identifié par l'en-tête `X-User-Id` (mécanisme simplifié accepté).

```bash
# 1. Tarifs
curl -s localhost:8080/api/document-types

# 2. Enregistrer une demande : le service indique le montant à payer
curl -s -X POST localhost:8080/api/document-requests \
  -H 'X-User-Id: alice' -H 'Content-Type: application/json' \
  -d '{"documentType":"CRIMINAL_RECORD","copies":2}'
# → "amountDue": 3100, "status": "UNPAID"

# 3. Lancer le paiement (Idempotency-Key : à générer une fois par tentative côté appli)
curl -s -X POST localhost:8080/api/document-requests/<requestId>/payments \
  -H 'X-User-Id: alice' -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 6f1c0a52-tentative-1' \
  -d '{"phoneNumber":"0197123456","operator":"MTN"}'
# → 202, "status": "PENDING"   (renvoyer la même requête → 200, même paiement, aucun débit de plus)

# 4. Environ 3 s plus tard, le simulateur rappelle le service ; l'usager consulte :
curl -s localhost:8080/api/payments/<paymentId> -H 'X-User-Id: alice'
# → "status": "SUCCEEDED"

# Voir ce que l'opérateur simulé a reçu
curl -s localhost:8080/simulator/transactions
```

Le simulateur choisit l'issue d'après la fin du numéro :

| Numéro se terminant par | Issue |
|---|---|
| `00` | échec (solde insuffisant) |
| `99` | aucune réponse (le paiement sera annulé par la réconciliation) |
| autre | succès |

En mode `simulator.mode=manual`, l'issue se déclenche à la main :
`POST /simulator/transactions/{paymentId}/result` avec `{"success":true}`,
et `POST /simulator/transactions/{paymentId}/resend` simule une relance (doublon).

---

## API

| Méthode | Route | Rôle |
|---|---|---|
| GET | `/api/document-types` | Tarifs |
| POST | `/api/document-requests` | Enregistrer une demande (`documentType`, `copies`) → montant à payer |
| GET | `/api/document-requests`, `/api/document-requests/{id}` | Mes demandes et leur statut : `UNPAID`, `PAYMENT_PENDING`, `PAID` |
| POST | `/api/document-requests/{id}/payments` | Lancer le paiement (`phoneNumber`, `operator`), en-tête `Idempotency-Key` facultatif |
| GET | `/api/document-requests/{id}/payments` | Historique des tentatives |
| GET | `/api/payments/{id}` | État d'un paiement, avec un message lisible |
| POST | `/api/operators/{operator}/results` | Rappel de l'opérateur, signé (`X-Signature`) |

Erreurs au format `application/problem+json` avec un `code` stable :
`INVALID_PHONE_NUMBER`, `PAYMENT_PENDING`, `REQUEST_ALREADY_PAID`,
`IDEMPOTENCY_KEY_REUSED`, `INVALID_SIGNATURE`, `AMOUNT_MISMATCH`,
`RESOURCE_NOT_FOUND`, `USER_NOT_IDENTIFIED`…

---

## Conception

### Cycle de vie d'un paiement

```
                 rappel signé SUCCESS / consultation
   PENDING ──────────────────────────────────────────► SUCCEEDED
      │  rappel signé FAILURE / refus / débit inconnu
      ├──────────────────────────────────────────────► FAILED
      │  aucun résultat après 10 min ET annulation confirmée
      └──────────────────────────────────────────────► EXPIRED
```

`SUCCEEDED`, `FAILED` et `EXPIRED` sont **définitifs**. Après `FAILED` ou `EXPIRED`, la
demande redevient payable (nouvelle tentative = nouveau paiement, l'historique est
conservé).

### Règles de gestion → mécanisme

**Montant calculé par le service.** `DocumentType.amountDue` (tarif × copies + 100),
en entiers (le FCFA n'a pas de centimes). Les types d'actes et leurs tarifs sont en
table `document_type`, alimentée au démarrage par `DocumentTypeSeeder` (idempotent :
un acte déjà présent n'est pas écrasé). Tarif et montant sont figés sur la demande,
puis le montant est copié sur le paiement : un changement de tarif ne modifie pas les
demandes existantes. Un code d'acte inconnu donne un 400 `UNKNOWN_DOCUMENT_TYPE`. Jackson est configuré pour refuser les champs inconnus : un `amount`
envoyé par le client produit un 400 au lieu d'être ignoré en silence.

**Numéro invalide = aucun débit.** Contrôle `01` + 8 chiffres avant toute écriture et
tout appel à l'opérateur.

**Une demande payée ou en cours ne peut pas être repayée.** La table `payment` a une
colonne `request_lock` = id de la demande tant que le paiement est `PENDING` ou
`SUCCEEDED`, `NULL` sinon, avec une **contrainte UNIQUE**. Les `NULL` n'entrant pas en
conflit, les nouvelles tentatives après échec restent possibles. C'est un index unique
partiel portable (H2 et PostgreSQL) : la règle est garantie par la base, pas par un
`if` qu'une requête concurrente pourrait contourner.

**Double envoi = un seul débit (idempotence).** Deux niveaux :
- sans clé : le second envoi heurte la contrainte unique → `409 PAYMENT_PENDING`
  avec l'id du paiement existant, sans débit ;
- avec `Idempotency-Key` (unique par usager) : le second envoi renvoie **le même
  paiement en 200**, ce qui permet à l'application de reprendre là où le réseau
  l'a coupée. Une clé réutilisée pour une autre requête donne un 422.

Par sécurité supplémentaire, l'identifiant du paiement est transmis à l'opérateur
comme référence : l'opérateur (simulé) dédoublonne aussi sur cette référence.

**Requêtes identiques simultanées (bonus).** L'insertion `PENDING` est validée
(commit) **avant** l'appel à l'opérateur, et seul le fil qui a réussi l'insertion
appelle l'opérateur. Le perdant reçoit une violation de contrainte, ne débite rien,
et renvoie le paiement gagnant (même clé) ou un 409. Testé avec 20 requêtes
lancées au même instant.

**Seuls les résultats authentiques comptent.** Le rappel est signé en HMAC-SHA256 sur
les **octets bruts** du corps, avec un secret propre à chaque opérateur (choisi d'après
l'URL `/api/operators/{operator}/…`). La signature est vérifiée avant toute lecture
du contenu, comparaison en temps constant. Ensuite : le paiement doit appartenir à cet
opérateur, et le montant annoncé doit être exactement le montant dû, sinon rejet
(422) sans modification.

**Un état final ne change plus.** La transition se fait sous verrou ligne
(`SELECT … FOR UPDATE`) et uniquement depuis `PENDING`. Un doublon ou un résultat
contradictoire est acquitté (200, `applied: false`) pour que l'opérateur cesse
ses relances, journalisé, et sans effet.

**Accusé ≠ résultat.** Si la demande de débit échoue sans issue certaine (délai
dépassé, réponse perdue), le paiement reste `PENDING` : conclure à un échec
permettrait un nouvel essai alors que le premier débit a pu passer.

### Paiements dont le résultat n'arrive jamais (bonus)

Une tâche planifiée (`payment.reconciliation.interval`, 30 s) reprend les paiements
`PENDING` :
1. après **2 min**, elle **interroge l'opérateur** et applique l'état qu'il connaît
   (succès, échec, ou débit jamais reçu → échec) ; c'est ce qui rattrape un rappel perdu ;
2. après **10 min**, si le débit est toujours en attente, elle en **demande
   l'annulation**. Le paiement ne passe `EXPIRED` (demande à nouveau payable) que si
   l'opérateur confirme l'annulation ; s'il répond que le débit a abouti entre-temps,
   le paiement passe `SUCCEEDED`. On ne libère donc jamais une demande tant qu'un débit
   peut encore aboutir.

### Isolation des usagers (bonus)

Toutes les lectures filtrent par usager (`findByIdAndUserId`). La ressource d'un
autre usager répond **404** et non 403, pour ne pas révéler son existence. Sans
`X-User-Id` valide : 401. En production, l'identité viendrait d'un jeton vérifié
(JWT / Spring Security) ; seul `CurrentUserResolver` changerait.

### Simulateur

`simulator/` contient l'opérateur simulé, le plus simplement possible : un registre
en mémoire et un rappel HTTP signé vers le service. Le service ne dépend que de
l'interface `OperatorClient` ; un client HTTP réel par opérateur s'y substituerait.
En production : `simulator.enabled=false`.

### Structure

```
request/     types d'actes, tarifs, demandes
payment/     lancement, résultats signés, réconciliation, états
operator/    contrat avec l'opérateur + signature HMAC
simulator/   opérateur simulé (hors périmètre évalué)
common/      identification de l'usager, erreurs
```

---

## Tests

`mvn test` lance :
- `BusinessRulesTest` : montants, format du numéro, signature, immutabilité d'un
  paiement finalisé ;
- `PaymentIntegrationTest` : serveur HTTP réel + base H2, simulateur en mode manuel.
  Cas couverts : montant calculé et montant client refusé, numéros invalides sans
  débit, parcours nominal, demande en cours / déjà payée, nouvel essai après échec,
  double envoi, **20 requêtes simultanées avec et sans clé → 1 débit**, rappel non
  signé / mal signé / signé par un autre opérateur / corps altéré, montant incohérent,
  doublon et résultat contradictoire après succès ou échec, résultat jamais reçu
  (annulation puis nouvel essai), rappel perdu, accusé perdu, isolation des usagers.

---

## Pour aller plus loin en production

- Horodatage signé dans le rappel et fenêtre de validité (les rejeux sont déjà sans
  effet, mais on limiterait le bruit).
- Rotation des secrets (deux secrets acceptés pendant la transition).
- Alertes sur les anomalies journalisées (montant incohérent, résultat contradictoire).
- Index unique partiel natif PostgreSQL (`WHERE status IN ('PENDING','SUCCEEDED')`)
  et migrations Flyway au lieu de `ddl-auto`.
- Rapprochement quotidien avec le relevé de l'opérateur.
