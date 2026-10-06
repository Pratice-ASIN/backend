package bj.taxstamp.payment.common;

/**
 * Usager à l'origine de la requête.
 *
 * Mécanisme d'identification volontairement simplifié (accepté par l'énoncé) :
 * l'identifiant est lu dans l'en-tête {@code X-User-Id}. En production il
 * proviendrait d'un jeton (JWT) vérifié par une passerelle ou Spring Security ;
 * seul {@link UsagerCourantResolver} serait à remplacer.
 */
public record CurrentUser(String id) {
}
