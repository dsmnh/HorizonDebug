package dev.dsmnh.horizon.debug.bot;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 捕获型命令发送者：把 {@code addChatMessage} 的内容收集成行，其余方法转发给真实发送者。
 *
 * <p>用途是让假玩家执行命令后能拿到回显。{@code MinecraftServer.getCommandManager()}
 * 只会把结果写进发送者的聊天栏，返回的整数只是命令自身的返回值，看不到输出内容。
 * 包一层之后既能保留真实权限判断，又能把输出原样带回来。
 */
final class CaptureSender implements ICommandSender {

    private final ICommandSender delegate;
    private final List<String> lines = new ArrayList<String>();

    CaptureSender(ICommandSender delegate) {
        this.delegate = delegate;
    }

    /** 已捕获的输出行（只读视图，行内容不含颜色码） */
    List<String> lines() {
        return Collections.unmodifiableList(lines);
    }

    @Override
    public String getCommandSenderName() {
        return delegate.getCommandSenderName();
    }

    @Override
    public IChatComponent func_145748_c_() {
        return delegate.func_145748_c_();
    }

    @Override
    public void addChatMessage(IChatComponent component) {
        lines.add(component == null ? "" : component.getUnformattedText());
    }

    @Override
    public boolean canCommandSenderUseCommand(int level, String command) {
        return delegate.canCommandSenderUseCommand(level, command);
    }

    @Override
    public ChunkCoordinates getPlayerCoordinates() {
        return delegate.getPlayerCoordinates();
    }

    @Override
    public World getEntityWorld() {
        return delegate.getEntityWorld();
    }
}
