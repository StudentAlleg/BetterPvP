package me.mykindos.betterpvp.champions.champions.skills.skills.assassin.data;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

@Data
@AllArgsConstructor
public class ComboAttackData {
    private UUID lastTarget;
    private double damageIncrement;
    private long last;
}

