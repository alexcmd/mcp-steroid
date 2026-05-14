ARG BASE_IMAGE
FROM ${BASE_IMAGE}

USER root
COPY mcp-steroid-proxy.zip /tmp/mcp-steroid-proxy.zip
RUN mkdir -p /opt/devrig && \
    unzip -q /tmp/mcp-steroid-proxy.zip -d /opt/devrig && \
    mv /opt/devrig/mcp-steroid-proxy-* /opt/devrig/app && \
    chmod +x /opt/devrig/app/bin/mcp-steroid-proxy && \
    ln -s /opt/devrig/app/bin/mcp-steroid-proxy /opt/devrig/app/bin/devrig && \
    rm /tmp/mcp-steroid-proxy.zip

USER agent
WORKDIR /home/agent
ENV PATH="/opt/devrig/app/bin:/home/agent/.local/bin:/usr/local/bin:/usr/bin:$PATH"
ENV HOME=/home/agent
ENV DISPLAY=:99

CMD ["sleep", "infinity"]
