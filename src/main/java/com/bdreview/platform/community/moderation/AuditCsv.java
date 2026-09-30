package com.bdreview.platform.community.moderation;

import com.bdreview.platform.moderation.AuditLog;

import java.util.List;

/** RFC-4180 CSV for audit-log exports (admin panel + JSON API share it). */
public final class AuditCsv {

    private AuditCsv() {
    }

    public static String write(List<AuditLog> rows) {
        StringBuilder sb = new StringBuilder("time,actor_id,actor_role,action,target_type,target_id,reason,ip,before,after\n");
        for (AuditLog a : rows) {
            sb.append(cell(String.valueOf(a.getCreatedAt()))).append(',')
                    .append(cell(String.valueOf(a.getPerformedByAdmin()))).append(',')
                    .append(cell(a.getActorRole())).append(',')
                    .append(cell(a.getAction())).append(',')
                    .append(cell(a.getEntityType())).append(',')
                    .append(cell(String.valueOf(a.getEntityId()))).append(',')
                    .append(cell(a.getReason() != null ? a.getReason() : a.getNotes())).append(',')
                    .append(cell(a.getIpAddress())).append(',')
                    .append(cell(a.getBeforeJson())).append(',')
                    .append(cell(a.getAfterJson())).append('\n');
        }
        return sb.toString();
    }

    private static String cell(String v) {
        if (v == null) {
            return "";
        }
        // Neutralise spreadsheet formula injection, then quote.
        String safe = !v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 ? "'" + v : v;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
