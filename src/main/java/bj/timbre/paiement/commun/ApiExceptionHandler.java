package bj.timbre.paiement.commun;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ErreurMetier.class)
    public ResponseEntity<ProblemDetail> erreurMetier(ErreurMetier e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(e.getStatut(), e.getMessage());
        pd.setProperty("code", e.getCode());
        e.getDetails().forEach(pd::setProperty);
        return ResponseEntity.status(e.getStatut()).body(pd);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException e) {
        Map<String, String> champs = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> champs.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Données invalides");
        pd.setProperty("code", "DONNEES_INVALIDES");
        pd.setProperty("champs", champs);
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> illisible(HttpMessageNotReadableException e) {
        // Couvre aussi les champs inconnus (ex. un "montant" fourni par l'usager)
        // et les valeurs d'énumération inconnues.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Corps de requête illisible ou contenant des champs non autorisés");
        pd.setProperty("code", "REQUETE_ILLISIBLE");
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> parametreInvalide(MethodArgumentTypeMismatchException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Paramètre invalide : " + e.getName());
        pd.setProperty("code", "PARAMETRE_INVALIDE");
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> inattendue(Exception e) {
        log.error("Erreur inattendue", e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Erreur interne, veuillez réessayer");
        pd.setProperty("code", "ERREUR_INTERNE");
        return ResponseEntity.internalServerError().body(pd);
    }
}
