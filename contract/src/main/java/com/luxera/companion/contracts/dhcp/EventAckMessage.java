package com.luxera.companion.contracts.dhcp;

/**
 * V10 §63 EVENT_ACK message. Client → server. Confirms receipt of an event; server uses this
 * to advance {@code client_sync_cursor.last_event_id} and to drop buffered events from the
 * in-memory resume window. Batch acks are allowed: pass the highest sequence acknowledged.
 */
public record EventAckMessage(String eventId, Long sequence) {}
