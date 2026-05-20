package com.example.redisproxy.controlplane.model;

import java.util.List;

public record ConfigDiff(long fromVersionId, long toVersionId, List<String> changes) {
}
