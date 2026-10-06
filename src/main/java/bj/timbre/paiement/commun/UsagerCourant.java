package bj.timbre.paiement.commun;

/**
 * Usager à l'origine de la requête.
 *
 * Mécanisme d'identification volontairement simplifié (accepté par l'énoncé) :
 * l'identifiant est lu dans l'en-tête {@code X-Usager-Id}. En production il
 * proviendrait d'un jeton (JWT) vérifié par une passerelle ou Spring Security ;
 * seul {@link UsagerCourantResolver} serait à remplacer.
 */
public record UsagerCourant(String id) {
}
