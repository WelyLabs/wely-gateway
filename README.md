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

Java 25 · Spring Boot 4 · **Spring Cloud Gateway** (WebFlux) · OAuth2 Resource Server · Resilience4j · Redis

---

## Routage

```
                              ┌──────────────────────────────┐
  navigateur                  │      wely-gateway        │
      │                       │           :8081              │
      │  /api/v1/**           │                              │
      ├──────────────────────▶│  1. CORS                     │
      │  /rsocket             │  2. validation JWT           │
      │                       │  3. quota par appelant       │
      │                       │  4. circuit breaker          │
      │                       │  5. stripPrefix(2)           │
      └──────────────────────▶│  6. routage + retry          │
                              └──────────────┬───────────────┘
                                             │
        ┌──────────────┬───────────────┬─────┴─────────┬──────────────┐
        ▼              ▼               ▼               ▼              ▼
  /user-service  /social-service  /chat-service  /events-service  /rsocket
      :8082          :8083           :8084           :8086          :8084
```

| Route entrante | Cible | Filtres |
|---|---|---|
| `/api/v1/user-service/**` | wely-users | quota · breaker · `stripPrefix(2)` · `retry(3)` |
| `/api/v1/social-service/**` | wely-social | quota · breaker · `stripPrefix(2)` · `retry(3)` |
| `/api/v1/chat-service/**` | wely-chat | quota · breaker · `stripPrefix(2)` · `retry(3)` |
| `/api/v1/events-service/**` | wely-events | quota · breaker · `stripPrefix(2)` · `retry(3)` |
| `/rsocket`, `/rsocket/**` | wely-chat (WebSocket) | *aucun* |

L'ordre des filtres n'est pas arbitraire. Le quota passe **avant** le breaker : un appelant qui
inonde la gateway est refoulé avant qu'on touche à quoi que ce soit en aval, budget d'échec du
breaker compris. Les `retry` se produisent **à l'intérieur** du breaker, donc une route réellement
tombée le fait déclencher plus tôt — c'est le comportement souhaitable.

La route RSocket ne porte aucun des trois, et c'est délibéré : tous comptent des requêtes, or un
WebSocket est **une** requête qui vit ensuite le temps de l'onglet. Un quota y plafonnerait le
nombre d'utilisateurs simultanés au lieu du volume d'appels, et le filtre du breaker lit l'échange
d'une manière qui casse la poignée de main d'upgrade. Protéger une connexion longue est un autre
problème, et compter des requêtes HTTP n'en est pas la réponse.

### Résilience

| Mécanisme | Implémentation | Ce qu'il empêche |
|---|---|---|
| Circuit breaker | Resilience4j, une instance par route | Un service en panne retient une connexion de la gateway pour chaque appelant en attente jusqu'à expiration. La gateway épuise ses connexions avant que le service ne revienne : un service tombé emporte la plateforme. |
| Quota par appelant | `RedisRateLimiter`, clé = `sub` du JWT | Une boucle côté client qui consomme la capacité de tout le monde. |
| Time limiter | 10 s | Un service lent est un service en panne : sans cela, un aval qui accepte la connexion et ne répond jamais n'est jamais compté comme un échec. |

Breaker ouvert → `FallbackController` répond **503** en `ProblemDetail` RFC 7807, la même forme
que les services derrière. 503 et non 500 : la requête n'a jamais été tentée, rien n'est corrompu,
et réessayer plus tard est la bonne réponse — ce sur quoi un client peut agir.

La clé du quota est le `sub` du JWT, pas l'adresse IP : derrière un tunnel Cloudflare toutes les
requêtes arrivent de quelques adresses, un quota par IP serait donc partagé par tout le monde et un
seul utilisateur intensif étranglerait les autres.

Une panne Redis **ne bloque pas** le trafic. `RedisRateLimiter` intercepte l'échec et laisse passer
la requête en journalisant l'erreur : le pire cas est du trafic non compté pendant la panne, pas une
plateforme injoignable. C'est le bon compromis pour un quota — et la raison pour laquelle cela
mérite une alerte plutôt qu'une confiance aveugle.

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
| `REDIS_HOST` / `REDIS_PORT` | Compteurs de quota (défaut : `localhost:6379`) |
| `RATE_LIMIT_REPLENISH_RATE` | Débit soutenu par appelant, en req/s (défaut : 20) |
| `RATE_LIMIT_BURST_CAPACITY` | Rafale autorisée (défaut : 40) |

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

- **Les seuils du breaker sont uniformes.** Une même configuration pour les cinq routes, alors que
  `wely-social` interroge Neo4j et `wely-users` PostgreSQL : leurs latences normales n'ont pas de
  raison d'être jugées au même étalon. À différencier une fois qu'il existe des mesures.
- **Le quota est par instance de Redis, pas par utilisateur authentifié au sens fort.** Une requête
  sans jeton retombe sur l'adresse de l'appelant, partagée derrière le tunnel Cloudflare.
- **Route fantôme** : `/api/v1/media-service/**` pointe vers un service supprimé du projet. La
  supprimer touche aussi `wely-gitops-infra`, qui injecte `MEDIA_API_URL`.
- **Redis n'est pas répliqué** : un seul pod, sans persistance. Acceptable pour des compteurs de
  quota — les perdre ne fait que remettre quelques appelants à zéro.
- **Pas de test d'intégration du quota.** Le comportement du limiteur face à un vrai Redis n'est pas
  couvert : il faudrait Testcontainers pour observer un 429.
