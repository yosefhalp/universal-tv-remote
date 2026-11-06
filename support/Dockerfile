FROM node:24-alpine@sha256:a34ab6d7f6f4813f4d0c5469d74685604b962fbdbf5793a7bc7b4998ecb867c8
WORKDIR /app
COPY support/package.json support/package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force
COPY support/server ./server
COPY support/web ./web
RUN mkdir /data && chown node:node /data
USER node
ENV HOST=0.0.0.0 PORT=8788 DATA_FILE=/data/devices.json NODE_ENV=production
EXPOSE 8788
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s CMD node -e "fetch('http://127.0.0.1:8788/health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"
CMD ["node","server/server.mjs"]
