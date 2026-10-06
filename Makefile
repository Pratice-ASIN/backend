.PHONY: help up down restart build logs ps db test run clean

help: ## Affiche cette aide
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*## "} {printf "  %-10s %s\n", $$1, $$2}'

up: ## Construit et démarre l'application + PostgreSQL (http://localhost:8080)
	docker compose up -d --build

down: ## Arrête les conteneurs (les données PostgreSQL sont conservées)
	docker compose down

restart: down up ## Redémarre la pile

build: ## Construit l'image de l'application
	docker compose build app

logs: ## Suit les logs de l'application
	docker compose logs -f app

ps: ## État des conteneurs
	docker compose ps

db: ## Ouvre un shell psql sur la base
	docker compose exec postgres psql -U taxstamp -d taxstamp

test: ## Lance les tests (Maven local)
	mvn test

run: ## Lance l'application en local sur H2 (sans Docker)
	mvn spring-boot:run

clean: ## Arrête la pile et supprime le volume PostgreSQL
	docker compose down -v
	mvn -q clean
