FROM node:22-alpine
WORKDIR /app
COPY . .
VOLUME /data
ENV RELAY_DATA_DIR=/data PORT=18421
EXPOSE 18421
CMD ["node", "server.mjs"]
