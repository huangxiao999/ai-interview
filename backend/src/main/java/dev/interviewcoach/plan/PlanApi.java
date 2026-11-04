package dev.interviewcoach.plan;

import dev.interviewcoach.identity.CurrentActor;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("local")
final class PlanApi {
    private final PlanService plans;
    private final CurrentActor actors;
    private final PlanRevisionService revisions;
    private final PlanHistoryService history;
    private final ControlledPlanWindow trial;
    PlanApi(PlanService plans,CurrentActor actors,PlanRevisionService revisions,PlanHistoryService history,ControlledPlanWindow trial){this.plans=plans;this.actors=actors;this.revisions=revisions;this.history=history;this.trial=trial;}
    @GetMapping("/api/plan-controlled-window")
    ResponseEntity<ControlledPlanWindow.Status> trial(Authentication auth){return result(200,trial.status(actors.require(auth)));}
    @GetMapping("/api/plan-controlled-window/request")
    ResponseEntity<PlanService.Prepared> trialRequest(Authentication auth,@RequestParam String kind){return result(200,plans.trialRequest(actors.require(auth),kind));}
    @PostMapping("/api/plan-controlled-window/approval")
    ResponseEntity<ControlledPlanWindow.Status> approve(Authentication auth,@RequestHeader("X-Expected-Account-Id")UUID expected,@RequestBody ControlledPlanWindow.Approval input){var actor=actors.require(auth);trial.approve(actor,expected,input);return result(200,trial.status(actor));}
    @PostMapping("/api/plan-controlled-window/bill")
    ResponseEntity<ControlledPlanWindow.Status> bill(Authentication auth,@RequestHeader("X-Expected-Account-Id")UUID expected,@RequestBody ControlledPlanWindow.Bill input){var actor=actors.require(auth);trial.bill(actor,expected,input);return result(200,trial.status(actor));}
    @PostMapping("/api/plan-controlled-window/stop")
    ResponseEntity<ControlledPlanWindow.Status> stop(Authentication auth,@RequestHeader("X-Expected-Account-Id")UUID expected){var actor=actors.require(auth);trial.stop(actor,expected);return result(200,trial.status(actor));}
    @GetMapping("/api/plan-workspaces")
    ResponseEntity<PlanHistoryService.Page> history(Authentication auth,@RequestParam(required=false) UUID resumeId,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit){return result(200,history.list(actors.require(auth),resumeId,cursor,limit));}
    @GetMapping("/api/plans/preparation")
    ResponseEntity<PlanService.Preparation> preparation(Authentication auth){return result(200,plans.preparation(actors.require(auth)));}
    @PostMapping("/api/plan-workspaces")
    ResponseEntity<PlanService.View> create(Authentication auth,@RequestHeader("X-Expected-Account-Id")UUID expected,
            @RequestHeader("X-Operation-Id")UUID operation,@RequestBody PlanPolicy.Input input){
        return result(201,plans.create(actors.require(auth),expected,operation,input));
    }
    @GetMapping("/api/plan-workspaces/{id}")
    ResponseEntity<PlanService.View> workspace(Authentication auth,@PathVariable UUID id){return result(200,plans.readWorkspace(actors.require(auth),id));}
    @PostMapping("/api/plan-workspaces/{id}/generation")
    ResponseEntity<PlanService.View> generate(Authentication auth,@PathVariable UUID id,@RequestHeader("X-Expected-Account-Id")UUID expected,
            @RequestHeader("X-Operation-Id")UUID operation){return result(202,plans.generate(actors.require(auth),expected,id,operation));}
    @GetMapping("/api/plan-operations/{id}")
    ResponseEntity<PlanService.View> operation(Authentication auth,@PathVariable UUID id,@RequestParam String kind){return result(200,plans.readOperation(actors.require(auth),id,kind));}
    @PostMapping("/api/plan-operations/{id}/resume")
    ResponseEntity<PlanService.View> resume(Authentication auth,@PathVariable UUID id,@RequestHeader("X-Expected-Account-Id")UUID expected,
            @RequestHeader("X-Operation-Id")UUID operation,@RequestBody PlanPolicy.Resume input){return result(202,plans.resume(actors.require(auth),expected,id,operation,input));}
    @GetMapping("/api/plans/{id}")
    ResponseEntity<PlanService.View> read(Authentication auth,@PathVariable UUID id){return result(200,plans.readPlan(actors.require(auth),id));}
    @GetMapping("/api/plans/{id}/revisions/current")
    ResponseEntity<PlanRevisionService.View> currentRevision(Authentication auth,@PathVariable UUID id){return result(200,revisions.current(actors.require(auth),id));}
    @GetMapping("/api/plans/{id}/revisions/{revision}")
    ResponseEntity<PlanRevisionService.View> revision(Authentication auth,@PathVariable UUID id,@PathVariable UUID revision){return result(200,revisions.read(actors.require(auth),id,revision));}
    @PostMapping("/api/plans/{id}/revisions")
    ResponseEntity<PlanRevisionService.View> save(Authentication auth,@PathVariable UUID id,@RequestHeader("X-Expected-Account-Id")UUID expected,
            @RequestHeader("X-Operation-Id")UUID operation,@RequestBody PlanRevisionPolicy.Save input){return result(201,revisions.save(actors.require(auth),expected,id,operation,input));}
    @PostMapping("/api/plans/{id}/confirmations")
    ResponseEntity<PlanRevisionService.View> confirm(Authentication auth,@PathVariable UUID id,@RequestHeader("X-Expected-Account-Id")UUID expected,
            @RequestHeader("X-Operation-Id")UUID operation,@RequestBody PlanRevisionPolicy.Confirm input){return result(200,revisions.confirm(actors.require(auth),expected,id,operation,input));}
    @GetMapping("/api/plan-revision-operations/{operation}")
    ResponseEntity<PlanRevisionService.View> revisionOperation(Authentication auth,@PathVariable UUID operation){return result(200,revisions.operation(actors.require(auth),operation));}
    private static <T> ResponseEntity<T> result(int status,T value){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(value);}
}
