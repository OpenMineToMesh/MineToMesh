package com.nebysse.minetomesh.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.nebysse.minetomesh.job.ExportJobManager;
import com.nebysse.minetomesh.job.ManagedJob;
import com.nebysse.minetomesh.output.ExportName;
import com.nebysse.minetomesh.sable.RegionExport;
import com.nebysse.minetomesh.world.BlockPoint;
import com.nebysse.minetomesh.world.Selection;
import com.nebysse.minetomesh.world.SelectionStore;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

public final class MineToMeshCommands {
    private final SelectionStore selectionStore;
    private final ExportJobManager jobManager;
    private final ExportJobFactory jobFactory;

    public MineToMeshCommands(
            SelectionStore selectionStore,
            ExportJobManager jobManager,
            ExportJobFactory jobFactory) {
        this.selectionStore = Objects.requireNonNull(selectionStore, "selectionStore");
        this.jobManager = Objects.requireNonNull(jobManager, "jobManager");
        this.jobFactory = Objects.requireNonNull(jobFactory, "jobFactory");
    }

    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        register(event.getDispatcher());
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("minetomesh")
                .then(Commands.literal("pos1")
                        .executes(context -> setPoint(1)))
                .then(Commands.literal("pos2")
                        .executes(context -> setPoint(2)))
                .then(Commands.literal("export")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(context -> export(context, false))
                                .then(Commands.literal("confirm")
                                        .executes(context -> export(context, true)))))
                .then(Commands.literal("status")
                        .executes(context -> status()))
                .then(Commands.literal("cancel")
                        .executes(context -> cancel()))
                // Sable region export: consumes the selection the Sable blueprint tool is showing
                // rather than pos1/pos2, because it exports what that tool selected - including
                // the structures Sable stores in sub-levels. See com.nebysse.minetomesh.sable.
                .then(Commands.literal("sable")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(MineToMeshCommands::exportSable))));
    }

    private static int exportSable(CommandContext<CommandSourceStack> context) {
        return RegionExport.run(context.getSource(),
                StringArgumentType.getString(context, "name"));
    }

    private int setPoint(int index) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            ClientMessages.send(ClientMessages.failed("No active client world"));
            return 0;
        }
        var position = minecraft.player.blockPosition();
        String dimension = minecraft.level.dimension().location().toString();
        selectionStore.clearIfDimensionChanged(dimension);
        BlockPoint point = new BlockPoint(dimension, position.getX(), position.getY(), position.getZ());
        if (index == 1) {
            selectionStore.setPos1(point);
        } else {
            selectionStore.setPos2(point);
        }
        ClientMessages.send(ClientMessages.pointSet(index, point));
        return 1;
    }

    private int export(CommandContext<CommandSourceStack> context, boolean confirmed) {
        Selection selection;
        try {
            selection = selectionStore.selection().orElse(null);
        } catch (IllegalArgumentException exception) {
            ClientMessages.send(ClientMessages.crossDimensionSelection());
            return 0;
        }
        if (selection == null) {
            ClientMessages.send(ClientMessages.incompleteSelection());
            return 0;
        }

        ExportName name;
        try {
            name = ExportName.parse(StringArgumentType.getString(context, "name"));
        } catch (IllegalArgumentException exception) {
            ClientMessages.send(ClientMessages.unsafeName(exception.getMessage()));
            return 0;
        }

        if (!confirmed && CommandPolicy.requiresConfirmation(selection.volume())) {
            String command = "/minetomesh export "
                    + StringArgumentType.escapeIfRequired(name.value()) + " confirm";
            ClientMessages.send(ClientMessages.confirmation(selection.volume(), command));
            return 0;
        }

        try {
            if (jobManager.activeJob().filter(job -> !job.isTerminal()).isPresent()) {
                ClientMessages.send(ClientMessages.alreadyRunning());
                return 0;
            }
            ManagedJob job = jobFactory.create(selection, name);
            if (!jobManager.start(job)) {
                job.cancel("rejected_already_running");
                ClientMessages.send(ClientMessages.alreadyRunning());
                return 0;
            }
            ClientMessages.send(ClientMessages.started(name.value()));
            return 1;
        } catch (Exception exception) {
            ClientMessages.send(ClientMessages.failed(exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage()));
            return 0;
        }
    }

    private int status() {
        var status = jobManager.status();
        if (status.isEmpty()) {
            ClientMessages.send(ClientMessages.idle());
            return 0;
        }
        ClientMessages.send(ClientMessages.status(status.orElseThrow()));
        return 1;
    }

    private int cancel() {
        var status = jobManager.status();
        if (status.isEmpty() || status.orElseThrow().state().isTerminal()) {
            ClientMessages.send(ClientMessages.idle());
            return 0;
        }
        jobManager.cancel("user");
        ClientMessages.send(ClientMessages.cancelled());
        return 1;
    }

    @FunctionalInterface
    public interface ExportJobFactory {
        ManagedJob create(Selection selection, ExportName name) throws Exception;
    }
}
