# RuntimeBroker Server - Dockerfile
# Multi-stage build for minimal production image

# Build stage
FROM node:18-alpine AS builder
WORKDIR /app

# Copy package files
COPY Server/package*.json ./

# Install production dependencies only
RUN npm ci --only=production

# Production stage
FROM node:18-alpine AS production
WORKDIR /app

# Create non-root user
RUN addgroup -g 1001 -S nodejs && \
    adduser -S nodejs -u 1001

# Copy built dependencies
COPY --from=builder --chown=nodejs:nodejs /app/node_modules ./node_modules
COPY --chown=nodejs:nodejs Server/ ./

# Switch to non-root user
USER nodejs

# Expose port
EXPOSE 3001

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:3001/api/health || exit 1

# Start server
CMD ["node", "server.js"]