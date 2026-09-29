# wely-gateway

**Point d'entrée unique** de la plateforme [Wely Calendar](https://github.com/WelyLabs/wely-platform). Tout le trafic client — REST et RSocket — transite par ce service.

---

## Rôle

| | |
|---|---|
| **Port** | 8081 |
| **Responsabilités** | Routage · validation JWT · CORS · proxy RSocket |

La gateway est le **seul composant exposé publiquement**. Les services métier ne sont joignables que depuis l'intérieur du cluster.

---

## Stack

Java 25 · Spring Boot 4 · **Spring Cloud Gateway** (WebFlux) · OAuth2 Resource Server

---

## Routage

```
                              ┌──────────────────────────────┐
  navigateur                  │      wely-gateway        │
      │                       │           :8081              │
      │  /api/v1/**           │                              │
      ├──────────────────────▶│  1. CORS                     │
      │  /rsocket             │  2. validation JWT           │
      │                       │  3. stripPrefix(2)           │
      └──────────────────────▶│  4. routage + retry          │
                              └──────────────┬───────────────┘
                                             │
        ┌──────────────┬───────────────┬─────┴─────────┬──────────────┐
        ▼              ▼               ▼               ▼              ▼
  /user-service  /social-service  /chat-service  /events-service  /rsocket
      :8082          :8083           :8084           :8086          :8084
```

| Route entrante | Cible | Transformation |
|---|---|---|
| `/api/v1/user-service/**` | wely-users | `stripPrefix(2)` + `retry(3)` |
| `/api/v1/social-service/**` | wely-social | `stripPrefix(2)` + `retry(3)` |
| `/api/v1/chat-service/**` | wely-chat | `stripPrefix(2)` + `retry(3)` |
| `/api/v1/events-service/**` | wely-events | `stripPrefix(2)` + `retry(3)` |
| `/rsocket`, `/rsocket/**` | wely-chat (WebSocket) | `retry(3)` |

### Le double préfixe

`stripPrefix(2)` retire `/api/v1`, mais **conserve** `/user-service`. Ce segment est réattaché côté service par sa propre configuration :

```java
@Configuration
@EnableWebFlux
public class WebConfig implements WebFluxConfigurer {
    @Override
    public void configurePathMatching(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/user-service",
                HandlerTypePredicate.forAnnotation(RestController.class));
    }
}
```

Conséquence : **un service répond sur le même chemin qu'il soit appelé via la gateway ou directement**, ce qui simplifie le développement local et le débogage. `/api/v1` reste le seul segment porté par la gateway, ce qui laisse la porte ouverte à un futur `/api/v2`.

### Le cas RSocket

RSocket over WebSocket n'est pas du HTTP requête/réponse : la gateway relaie la connexion sans l'interpréter, et l'authentification est déléguée à `wely-chat`, qui valide le JWT transmis dans la métadonnée d'authentification RSocket à chaque payload.

---

## Sécurité

```java
.csrf(ServerHttpSecurity.CsrfSpec::disable)      // API sans cookie de session
.cors(Customizer.withDefaults())
.authorizeExchange(exchanges -> exchanges
        .pathMatchers(HttpMethod.OPTIONS).permitAll()   // préflight
        .pathMatchers("/rsocket/**").permitAll()        // auth déléguée à wely-chat
        .pathMatchers("/public/**").permitAll()
        .anyExchange().authenticated())
.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
```

Le principe est **deny by default** : toute route non listée exige un JWT valide. La gateway rejette les requêtes non authentifiées avant même de contacter un service.

### Validation du JWT derrière un ingress

Comme tous les services de la plateforme, la gateway dissocie deux URL Keycloak :

```properties
# récupération des clés : service interne au cluster, pas de sortie réseau
spring.security.oauth2.resourceserver.jwt.jwk-set-uri=${KEYCLOAK_INTERNAL_JWK_SET_URI}
# validation de l'émetteur : URL publique, celle inscrite dans le claim iss
spring.security.oauth2.resourceserver.jwt.issuer-uri=${KEYCLOAK_ISSUER_URI}
```

Sans cette dissociation, soit la validation de l'`iss` échoue — l'URL interne ne correspond pas à celle du token —, soit le service sort du cluster à chaque rotation de clés.

Le décodeur est composé explicitement pour valider à la fois l'émetteur et l'horodatage :

```java
OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
        new JwtTimestampValidator(),
        JwtValidators.createDefaultWithIssuer(publicIssuer));
```

### CORS

Une seule origine autorisée, injectée par l'environnement (`CORS_ALLOWED_ORIGIN`), avec `allowCredentials(true)`. Pas de joker.

---

## Configuration

| Variable | Description |
|---|---|
| `USERS_API_URL` | URL interne de wely-users |
| `SOCIAL_API_URL` | URL interne de wely-social |
| `CHAT_API_URL` | URL interne de wely-chat |
| `CHAT_RSOCKET_URL` | URL WebSocket de wely-chat (`ws://…`) |
| `EVENTS_API_URL` | URL interne de wely-events |
| `CORS_ALLOWED_ORIGIN` | Origine autorisée (défaut : `https://web.welylabs.app`) |
| `KEYCLOAK_ISSUER_URI` | Issuer public |
| `KEYCLOAK_INTERNAL_JWK_SET_URI` | JWKS interne |

---

## Démarrage

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Le profil `dev` route vers `localhost` sur les ports 8082 à 8086 et autorise l'origine `http://localhost:4200`.

Pour lancer **toute la plateforme** (bases, Keycloak, gateway, frontend, les quatre services) en une commande sur un Kubernetes local :

```bash
git clone https://github.com/WelyLabs/wely-gitops-infra && cd wely-gitops-infra
kubectl apply -k overlays/local --server-side
```

---

## Tests

```bash
./mvnw test
./mvnw test jacoco:report      # → target-maven/site/jacoco/
```

4 classes de test : configuration de sécurité, table de routage, démarrage du contexte.

> **Note build :** ce service produit dans `target-maven/` et non `target/`.

---

## Limites connues

- **Pas de circuit breaker.** Les routes ont un `retry(3)` (GET et 5xx uniquement, par défaut), mais rien n'isole un service en panne : une dépendance lente dégrade toute la gateway. Resilience4j est le prochain chantier.
- **Pas de rate limiting.** La dépendance `spring-boot-starter-data-redis-reactive` est déclarée en prévision d'un `RequestRateLimiter`, mais n'est pas encore utilisée.
- **Pas de timeouts explicites** par route.
- **Route fantôme** : `/api/v1/media-service/**` pointe vers un service supprimé du projet.
- **La table de routage est dans la classe principale** plutôt que dans une `@Configuration` dédiée.
- **Logs trop verbeux en production** : `reactor.netty` et `spring.cloud.gateway` sont en `DEBUG`.
