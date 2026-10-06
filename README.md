# Paiement du timbre fiscal par mobile money

Service de paiement fiable (Spring Boot 3, Java 17) : un usager enregistre une demande
d'acte, le service calcule le montant, lance le débit chez l'opérateur (MTN, MOOV,
Celtiis) puis prend en compte le résultat signé que l'opérateur renvoie plus tard.

La première version avait produit trois incidents en production. Chacun a une
parade explicite :

| Incident | Cause probable | Parade dans ce service |
|---|---|---|
| **Doubles débits** | Double envoi (réseau mobile instable), requêtes simultanées, débit demandé avant d'avoir verrouillé la demande | Contrainte **UNIQUE en base** (un seul paiement en cours ou réussi par demande), clé d'idempotence, appel à l'opérateur **après** commit et par le seul gagnant |
| **Montants erronés** | Montant envoyé par l'application cliente | Montant **calculé par le service** et figé ; tout champ `montant` envoyé est rejeté ; montant du rappel contrôlé |
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

Les secrets partagés avec les opérateurs se surchargent par variables
d'environnement : `SECRET_MTN`, `SECRET_MOOV`, `SECRET_CELTIIS`.

---

## Démonstration (curl)

L'usager est identifié par l'en-tête `X-Usager-Id` (mécanisme simplifié accepté).

```bash
# 1. Tarifs
curl -s localhost:8080/api/types-actes

# 2. Enregistrer une demande : le service indique le montant à payer
curl -s -X POST localhost:8080/api/demandes \
  -H 'X-Usager-Id: alice' -H 'Content-Type: application/json' \
  -d '{"typeActe":"CASIER_JUDICIAIRE","nombreCopies":2}'
# → "montantAPayer": 3100, "statut": "A_PAYER"

# 3. Lancer le paiement (Idempotency-Key : à générer une fois par tentative côté appli)
curl -s -X POST localhost:8080/api/demandes/<demandeId>/paiements \
  -H 'X-Usager-Id: alice' -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 6f1c0a52-tentative-1' \
  -d '{"telephone":"0197123456","operateur":"MTN"}'
# → 202, "statut": "EN_COURS"   (renvoyer la même requête → 200, même paiement, aucun débit de plus)

# 4. Environ 3 s plus tard, le simulateur rappelle le service ; l'usager consulte :
curl -s localhost:8080/api/paiements/<paiementId> -H 'X-Usager-Id: alice'
# → "statut": "REUSSI"

# Voir ce que l'opérateur simulé a reçu
curl -s localhost:8080/simulateur/transactions
```

Le simulateur choisit l'issue d'après la fin du numéro :

| Numéro se terminant par | Issue |
|---|---|
| `00` | échec (solde insuffisant) |
| `99` | aucune réponse (le paiement sera annulé par la réconciliation) |
| autre | succès |

En mode `simulateur.mode=manuel`, l'issue se déclenche à la main :
`POST /simulateur/transactions/{paiementId}/resultat` avec `{"succes":true}`,
et `POST /simulateur/transactions/{paiementId}/renvoyer` simule une relance (doublon).

---

## API

| Méthode | Route | Rôle |
|---|---|---|
| GET | `/api/types-actes` | Tarifs |
| POST | `/api/demandes` | Enregistrer une demande (`typeActe`, `nombreCopies`) → montant à payer |
| GET | `/api/demandes`, `/api/demandes/{id}` | Mes demandes et leur statut : `A_PAYER`, `PAIEMENT_EN_COURS`, `PAYEE` |
| POST | `/api/demandes/{id}/paiements` | Lancer le paiement (`telephone`, `operateur`), en-tête `Idempotency-Key` facultatif |
| GET | `/api/demandes/{id}/paiements` | Historique des tentatives |
| GET | `/api/paiements/{id}` | État d'un paiement, avec un message lisible |
| POST | `/api/operateurs/{operateur}/resultats` | Rappel de l'opérateur, signé (`X-Signature`) |

Erreurs au format `application/problem+json` avec un `code` stable :
`TELEPHONE_INVALIDE`, `PAIEMENT_EN_COURS`, `DEMANDE_DEJA_PAYEE`,
`CLE_IDEMPOTENCE_REUTILISEE`, `SIGNATURE_INVALIDE`, `MONTANT_INCOHERENT`,
`RESSOURCE_INTROUVABLE`, `USAGER_NON_IDENTIFIE`…

---

## Conception

### Cycle de vie d'un paiement

```
                 rappel signé SUCCES / consultation
   EN_COURS ─────────────────────────────────────────► REUSSI
      │  rappel signé ECHEC / refus / débit inconnu
      ├──────────────────────────────────────────────► ECHOUE
      │  aucun résultat après 10 min ET annulation confirmée
      └──────────────────────────────────────────────► EXPIRE
```

`REUSSI`, `ECHOUE` et `EXPIRE` sont **définitifs**. Après `ECHOUE` ou `EXPIRE`, la
demande redevient payable (nouvelle tentative = nouveau paiement, l'historique est
conservé).

### Règles de gestion → mécanisme

**Montant calculé par le service.** `TypeActe.montantAPayer` (tarif × copies + 100),
en entiers (le FCFA n'a pas de centimes). Il est figé sur la demande puis copié sur
le paiement. Jackson est configuré pour refuser les champs inconnus : un `montant`
envoyé par le client produit un 400 au lieu d'être ignoré en silence.

**Numéro invalide = aucun débit.** Contrôle `01` + 8 chiffres avant toute écriture et
tout appel à l'opérateur.

**Une demande payée ou en cours ne peut pas être repayée.** La table `paiement` a une
colonne `demande_verrou` = id de la demande tant que le paiement est `EN_COURS` ou
`REUSSI`, `NULL` sinon, avec une **contrainte UNIQUE**. Les `NULL` n'entrant pas en
conflit, les nouvelles tentatives après échec restent possibles. C'est un index unique
partiel portable (H2 et PostgreSQL) : la règle est garantie par la base, pas par un
`if` qu'une requête concurrente pourrait contourner.

**Double envoi = un seul débit (idempotence).** Deux niveaux :
- sans clé : le second envoi heurte la contrainte unique → `409 PAIEMENT_EN_COURS`
  avec l'id du paiement existant, sans débit ;
- avec `Idempotency-Key` (unique par usager) : le second envoi renvoie **le même
  paiement en 200**, ce qui permet à l'application de reprendre là où le réseau
  l'a coupée. Une clé réutilisée pour une autre requête donne un 422.

Par sécurité supplémentaire, l'identifiant du paiement est transmis à l'opérateur
comme référence : l'opérateur (simulé) dédoublonne aussi sur cette référence.

**Requêtes identiques simultanées (bonus).** L'insertion `EN_COURS` est validée
(commit) **avant** l'appel à l'opérateur, et seul le fil qui a réussi l'insertion
appelle l'opérateur. Le perdant reçoit une violation de contrainte, ne débite rien,
et renvoie le paiement gagnant (même clé) ou un 409. Testé avec 20 requêtes
lancées au même instant.

**Seuls les résultats authentiques comptent.** Le rappel est signé en HMAC-SHA256 sur
les **octets bruts** du corps, avec un secret propre à chaque opérateur (choisi d'après
l'URL `/api/operateurs/{operateur}/…`). La signature est vérifiée avant toute lecture
du contenu, comparaison en temps constant. Ensuite : le paiement doit appartenir à cet
opérateur, et le montant annoncé doit être exactement le montant dû, sinon rejet
(422) sans modification.

**Un état final ne change plus.** La transition se fait sous verrou ligne
(`SELECT … FOR UPDATE`) et uniquement depuis `EN_COURS`. Un doublon ou un résultat
contradictoire est acquitté (200, `prisEnCompte: false`) pour que l'opérateur cesse
ses relances, journalisé, et sans effet.

**Accusé ≠ résultat.** Si la demande de débit échoue sans issue certaine (délai
dépassé, réponse perdue), le paiement reste `EN_COURS` : conclure à un échec
permettrait un nouvel essai alors que le premier débit a pu passer.

### Paiements dont le résultat n'arrive jamais (bonus)

Une tâche planifiée (`paiement.reconciliation.intervalle`, 30 s) reprend les paiements
`EN_COURS` :
1. après **2 min**, elle **interroge l'opérateur** et applique l'état qu'il connaît
   (succès, échec, ou débit jamais reçu → échec) ; c'est ce qui rattrape un rappel perdu ;
2. après **10 min**, si le débit est toujours en attente, elle en **demande
   l'annulation**. Le paiement ne passe `EXPIRE` (demande à nouveau payable) que si
   l'opérateur confirme l'annulation ; s'il répond que le débit a abouti entre-temps,
   le paiement passe `REUSSI`. On ne libère donc jamais une demande tant qu'un débit
   peut encore aboutir.

### Isolation des usagers (bonus)

Toutes les lectures filtrent par usager (`findByIdAndUsagerId`). La ressource d'un
autre usager répond **404** et non 403, pour ne pas révéler son existence. Sans
`X-Usager-Id` valide : 401. En production, l'identité viendrait d'un jeton vérifié
(JWT / Spring Security) ; seul `UsagerCourantResolver` changerait.

### Simulateur

`simulateur/` contient l'opérateur simulé, le plus simplement possible : un registre
en mémoire et un rappel HTTP signé vers le service. Le service ne dépend que de
l'interface `OperateurClient` ; un client HTTP réel par opérateur s'y substituerait.
En production : `simulateur.actif=false`.

### Structure

```
demande/     types d'actes, tarifs, demandes
paiement/    lancement, résultats signés, réconciliation, états
operateur/   contrat avec l'opérateur + signature HMAC
simulateur/  opérateur simulé (hors périmètre évalué)
commun/      identification de l'usager, erreurs
```

---

## Tests

`mvn test` lance :
- `ReglesMetierTest` : montants, format du numéro, signature, immutabilité d'un
  paiement finalisé ;
- `PaiementIntegrationTest` : serveur HTTP réel + base H2, simulateur en mode manuel.
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
- Index unique partiel natif PostgreSQL (`WHERE statut IN ('EN_COURS','REUSSI')`)
  et migrations Flyway au lieu de `ddl-auto`.
- Rapprochement quotidien avec le relevé de l'opérateur.
