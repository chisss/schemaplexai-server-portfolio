package com.schemaplexai.model.vo.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WebSocket Ticket 响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WsTicketVO {

    /**
     * 一次性 ws ticket
     */
    private String wsTicket;
}
