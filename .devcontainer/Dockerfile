FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

# Install essential tools
RUN apt-get update && \
    apt-get install -y vim curl wget git jq && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Install sbt
RUN curl -fL "https://github.com/sbt/sbt/releases/download/v1.12.0/sbt-1.12.0.tgz" | tar xz -C /tmp && \
    mv /tmp/sbt/bin/sbt /usr/local/bin/ && \
    mv /tmp/sbt/bin/sbt-launch.jar /usr/local/bin/ && \
    chmod +x /usr/local/bin/sbt && \
    rm -rf /tmp/sbt

# Set up Claude Code settings directory and default configuration
USER vscode
RUN mkdir -p ~/.claude && \
    echo '{\n  "permissions": {\n    "defaultMode": "bypassPermissions"\n  }\n}' > ~/.claude/settings.json

# Add ANTHROPIC_API_KEY export to .bashrc
RUN echo '\n# Export ANTHROPIC_API_KEY if set in environment\nif [ -n "$ANTHROPIC_API_KEY" ]; then\n  export ANTHROPIC_API_KEY\nfi' >> ~/.bashrc

USER root
