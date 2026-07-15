# Flux TTS (Java) Makefile
# Framework-agnostic commands for managing the project and git submodules

JAR := target/java-flux-tts-1.0.0-shaded.jar

.PHONY: help check check-prereqs init install start start-backend start-frontend test update clean status eject-frontend

help:
	@echo "Java Flux TTS - Available Commands"
	@echo "=================================="
	@echo ""
	@echo "Setup:"
	@echo "  make check-prereqs   Check required tools are installed"
	@echo "  make init            Initialize submodules and build the backend"
	@echo "  make install         Build the backend jar only"
	@echo ""
	@echo "Development:"
	@echo "  make start           Start the backend (port 8081)"
	@echo ""
	@echo "Maintenance:"
	@echo "  make update          Update submodules to latest commits"
	@echo "  make clean           Remove build artifacts"
	@echo "  make status          Show git and submodule status"
	@echo ""

check-prereqs:
	@command -v git >/dev/null 2>&1 || { echo "❌ git is required but not installed. Visit https://git-scm.com"; exit 1; }
	@command -v java >/dev/null 2>&1 || { echo "❌ java is required but not installed. Visit https://adoptium.net"; exit 1; }
	@command -v mvn >/dev/null 2>&1 || { echo "❌ maven is required but not installed. Visit https://maven.apache.org"; exit 1; }
	@echo "✓ All prerequisites installed"

check: check-prereqs

init: check-prereqs
	@echo "==> Initializing submodules (if any)..."
	git submodule update --init --recursive
	@echo ""
	@echo "==> Building backend..."
	mvn package -q -DskipTests
	@echo ""
	@echo "✓ Project initialized successfully!"
	@echo ""
	@echo "Next steps:"
	@echo "  1. Copy sample.env to .env and add your DEEPGRAM_API_KEY"
	@echo "  2. Run 'make start'"
	@echo ""

install:
	@echo "==> Building backend jar..."
	mvn package -q -DskipTests

start:
	@if [ -d "frontend" ] && [ -n "$$(ls -A frontend 2>/dev/null)" ]; then \
		$(MAKE) start-backend & $(MAKE) start-frontend & wait; \
	else \
		$(MAKE) start-backend; \
	fi

start-backend:
	@if [ ! -f ".env" ]; then \
		echo "❌ Error: .env file not found. Copy sample.env to .env and add your DEEPGRAM_API_KEY"; \
		exit 1; \
	fi
	@if [ ! -f "$(JAR)" ]; then \
		echo "==> Jar not found, building..."; \
		mvn package -q -DskipTests; \
	fi
	@echo "==> Starting backend on http://localhost:8081"
	java -jar $(JAR)

start-frontend:
	@if [ ! -d "frontend" ] || [ -z "$$(ls -A frontend 2>/dev/null)" ]; then \
		echo "Error: Frontend submodule not present yet (flux-tts-html)."; \
		exit 1; \
	fi
	@echo "==> Starting frontend on http://localhost:8080"
	cd frontend && pnpm run dev -- --port 8080 --no-open

update:
	@echo "==> Updating submodules..."
	git submodule update --remote --merge
	@echo "✓ Submodules updated"

test:
	@if [ ! -f ".env" ]; then \
		echo "❌ Error: .env file not found. Copy sample.env to .env and add your DEEPGRAM_API_KEY"; \
		exit 1; \
	fi
	@if [ ! -f "contracts/tests/run-flux-tts-app.sh" ]; then \
		echo "⚠️  Contract test not present yet (contracts/tests/run-flux-tts-app.sh)."; \
		echo "    Add it to deepgram/starter-contracts, then wire the contracts submodule."; \
		exit 1; \
	fi
	@echo "==> Running contract conformance tests..."
	@bash contracts/tests/run-flux-tts-app.sh

clean:
	@echo "==> Cleaning build artifacts..."
	rm -rf target
	rm -rf frontend/node_modules
	rm -rf frontend/dist
	@echo "✓ Cleaned successfully"

status:
	@echo "==> Repository Status"
	@echo "====================="
	@git status --short
	@echo ""
	@echo "Submodule Status:"
	@git submodule status || echo "(no submodules yet)"

eject-frontend:
	@echo ""
	@echo "⚠️  This will:"
	@echo "   1. Copy frontend submodule files into a regular 'frontend/' directory"
	@echo "   2. Remove the frontend git submodule configuration"
	@echo "   3. Remove the contracts git submodule"
	@echo "   4. Remove .gitmodules file"
	@echo ""
	@echo "   After ejecting, frontend changes can be committed directly"
	@echo "   with your backend changes. This cannot be undone."
	@echo ""
	@read -p "   Continue? [Y/n] " confirm; \
	if [ "$$confirm" != "Y" ] && [ "$$confirm" != "y" ] && [ -n "$$confirm" ]; then \
		echo "   Cancelled."; \
		exit 1; \
	fi
	@echo ""
	@echo "==> Ejecting frontend submodule..."
	@FRONTEND_TMP=$$(mktemp -d); \
	cp -r frontend/. "$$FRONTEND_TMP/"; \
	git submodule deinit -f frontend; \
	git rm -f frontend; \
	rm -rf .git/modules/frontend; \
	mkdir -p frontend; \
	cp -r "$$FRONTEND_TMP/." frontend/; \
	rm -rf "$$FRONTEND_TMP"; \
	rm -rf frontend/.git; \
	echo "   ✅ Frontend ejected to regular directory"
	@echo "==> Removing contracts submodule..."
	@if git config --file .gitmodules submodule.contracts.url > /dev/null 2>&1; then \
		git submodule deinit -f contracts; \
		git rm -f contracts; \
		rm -rf .git/modules/contracts; \
		echo "   ✅ Contracts submodule removed"; \
	else \
		echo "   ℹ️  No contracts submodule found"; \
	fi
	@if [ -f .gitmodules ] && [ ! -s .gitmodules ]; then \
		git rm -f .gitmodules; \
		echo "   ✅ Empty .gitmodules removed"; \
	fi
	@echo ""
	@echo "✅ Eject complete! Frontend files are now regular tracked files."
	@echo "   Run 'git add . && git commit' to save the changes."
