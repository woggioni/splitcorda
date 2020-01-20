package net.corda.splitcorda.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.undertow.Handlers
import io.undertow.Undertow
import io.undertow.server.handlers.resource.ClassPathResourceManager
import io.undertow.server.handlers.resource.ResourceHandler
import io.undertow.servlet.Servlets
import io.undertow.util.Headers
import org.jboss.resteasy.cdi.CdiInjectorFactory
import org.jboss.resteasy.core.ResteasyDeploymentImpl
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer
import org.jboss.weld.environment.servlet.Listener
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.enterprise.context.ApplicationScoped
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import javax.ws.rs.ApplicationPath
import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.core.Application
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import javax.ws.rs.core.StreamingOutput
import javax.enterprise.inject.Produces as Bean

@ApplicationScoped
open class ServerModule {
    @Bean
    open fun objectMapper(): ObjectMapper {
        val objectMapper = ObjectMapper()
        return objectMapper
    }
}

@Singleton
@Path("split")
class SplitCordaService {

    @Inject
    var objectMapper: ObjectMapper? = null

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("single")
    fun httpGetSingle(): StreamingOutput {
        return object : StreamingOutput {
            override fun write(output: OutputStream?) {
                val arr = JsonNodeFactory.instance.arrayNode()
                (0 until 10).forEach {
                    arr.add(it)
                }
                objectMapper!!.writeValue(output, arr)
            }
        }
    }

    @GET
    @Path("hello")
    @Produces(value = [MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN])
    fun hello(): Response {
        val result = "Hello REST World"
        return Response.status(Response.Status.OK).entity(result).build()
    }
}

@ApplicationPath("/")
object RestEasyServices : Application() {
//    private val singletons: Set<Any> = setOf(SplitCordaService)
//
//    override fun getSingletons(): Set<Any> {
//        return singletons
//    }

    override fun getClasses(): Set<Class<*>> {
        return setOf(SplitCordaService::class.java)
    }
}


object Server {

    @JvmStatic
    fun main(args: Array<String>) {
        val server = UndertowJaxrsServer()
        ResteasyDeploymentImpl().run {
            application = RestEasyServices
            injectorFactoryClass = CdiInjectorFactory::class.java.name
            server.undertowDeployment(this, "/api").run {
                classLoader = Server::class.java.classLoader
                deploymentName = "Minimal Undertow RESTeasy and Weld CDI Setup" // set name of deployment
                contextPath = "/"
                resourceManager = ClassPathResourceManager(this.javaClass.classLoader, "static")
                addListener(Servlets.listener(Listener::class.java))
                server.deploy(this)
            }
        }

        val resHandler = ResourceHandler(ClassPathResourceManager(this.javaClass.classLoader))
        val builder = Undertow.builder()
            .addHttpListener(8080, "localhost")
            .setHandler(resHandler)
        server.start(builder)

    }

    fun main2() {
        val resHandler = ResourceHandler(ClassPathResourceManager(Server.javaClass.classLoader, "static"))
        Undertow.builder().addHttpListener(8080, "0.0.0.0")
            .setHandler(
                Handlers.path()
                    .addPrefixPath("/", resHandler)
                    .addPrefixPath("/api", Handlers.routing()
                        .get("/hello") { exchange ->
                            exchange.run {
                                responseHeaders.put(Headers.CONTENT_TYPE, "text/plain")
                                val byteBuffer = ByteBuffer.wrap("Hello REST World".toByteArray())
                                responseChannel.write(byteBuffer)
                            }
                        })
            ).build().start()
    }
}