package com.davidneto.homepage.cameras;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "camera_setting")
public class CameraSetting {
    @Id
    @Column(length = 64) private String name;
    @Column(nullable = false, length = 500) private String value;

    public CameraSetting() {
    }

    public CameraSetting(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public String getName() { return name; }
    public String getValue() { return value; }
    public void setValue(String v) { this.value = v; }
}
