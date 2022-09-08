[![Build Status](https://jenkins.frengor.com/job/LightInjector/badge/icon)](https://jenkins.frengor.com/job/LightInjector/)
[![License](https://img.shields.io/badge/license-MIT-orange)](LICENSE)

# LightInjector
A light yet complete and fast packet injector for Spigot servers.

## How to use

Copy and paste the [`LightInjector`](src/main/java/com/fren_gor/lightInjector/LightInjector.java) class inside your plugin or shade it using maven.
<details>
<summary>Maven Instructions</summary>
<p>

```xml
<repositories>
    <repository>
        <id>fren_gor</id>
        <url>https://nexus.frengor.com/repository/public/</url>
    </repository>
</repositories>
```
```xml
<dependency>
    <groupId>com.frengor</groupId>
    <artifactId>lightinjector</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>compile</scope>
</dependency>
```
**It's suggested to shade it:**
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>3.3.0</version>

    <configuration>
        <relocation>
            <pattern>com.fren_gor.lightInjector</pattern>
            <shadedPattern>your.shaded.path.com.fren_gor.lightInjector</shadedPattern>
        </relocation>
    </configuration>

    <executions>
        <execution>
            <phase>package</phase>
            <goals>
                <goal>shade</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

</p>
</details>

Extend the `LightInjector` class and implement the `onPacketReceiveAsync` and `onPacketSendAsync` methods.
These methods will be called, respectively, when the server receives/sends a packet from/to a player.

```java
public class YourClass extends LightInjector {

    public YourClass(@NotNull Plugin plugin) {
        super(plugin); // This initializes the injector when YourClass will be constructed
    }

    @Override
    protected @Nullable Object onPacketReceiveAsync(@Nullable Player sender, @NotNull Channel channel, @NotNull Object nmsPacket) {
        // This will be called (asynchronously) when the server receives a packet from a player
        // The sender may be null for early login packets, so always check for it

        // Return the packet which will be received from the server, which can be different from the original packet.
        // Return null to cancel the packet.
        return nmsPacket;
    }

    @Override
    protected @Nullable Object onPacketSendAsync(@Nullable Player receiver, @NotNull Channel channel, @NotNull Object nmsPacket) {
        // This will be called (asynchronously) when the server sends a packet to a player
        // The receiver may be null for early login packets, so always check for it

        // Return the packet which will be sent to the player, which can be different from the original packet.
        // Return null to cancel the packet.
        return nmsPacket;
    }
}
```

Then create an instance of `YourClass` to start listening to packets.

```java
public class MainClass extends JavaPlugin {
    private YourClass injector;

    @Override
    public void onEnable() {
        injector = new YourClass(this);
    }
}
```

LightInjector will deinitialize itself automatically when the plugin disables, or you can do so manually by using the `close()` method.  
Packets will not be intercepted anymore after calling the `close()` method.

> Calling the `close()` method manually is usually not necessary, **unless** you want to stop listening to packets **before** your plugin disables.

## License

This project is licensed under the [MIT license](LICENSE).
