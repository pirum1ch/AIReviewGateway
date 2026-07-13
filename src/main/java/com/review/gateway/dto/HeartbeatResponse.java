package com.review.gateway.dto;

/** {@code POST /jobs/{id}/heartbeat} response (architecture §11); {@code shouldContinue=false} tells the Worker to stop (req. 1.7). */
public record HeartbeatResponse(boolean shouldContinue) {
}
