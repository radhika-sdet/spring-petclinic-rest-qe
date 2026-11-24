package org.springframework.samples.petclinic.rest.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.samples.petclinic.model.Pet;
import org.springframework.samples.petclinic.service.ClinicService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests verifying nested resource ownership and existence checks
 * for Owner -> Pet -> Visit endpoints against the real H2 persistence layer.
 */
@SpringBootTest
@Transactional
class OwnerNestedResourceOwnershipTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ClinicService clinicService;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        this.mockMvc = MockMvcBuilders
            .webAppContextSetup(context)
            .build();
    }

    @Test
    @WithMockUser(roles = "OWNER_ADMIN")
    void updateOwnersPetRejectsPetBelongingToDifferentOwner() throws Exception {
        // Seed data: Owner 1 owns Pet 1 ("Leo"); Owner 2 owns Pet 2 ("Basil")
        Pet owner2PetBefore = clinicService.findPetById(2);
        assertThat(owner2PetBefore).isNotNull();
        assertThat(owner2PetBefore.getOwner().getId()).isEqualTo(2);
        String originalName = owner2PetBefore.getName();

        String updatePayload = """
            {
              "name": "HijackedName",
              "birthDate": "2020-09-07",
              "type": { "id": 1, "name": "cat" }
            }
            """;

        this.mockMvc.perform(put("/api/owners/1/pets/2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload))
            .andExpect(status().isNotFound());

        Pet owner2PetAfter = clinicService.findPetById(2);
        assertThat(owner2PetAfter.getName()).isEqualTo(originalName);
    }

    @Test
    @WithMockUser(roles = "OWNER_ADMIN")
    void updateOwnersPetSucceedsWhenPetBelongsToOwner() throws Exception {
        String updatePayload = """
            {
              "name": "LeoUpdated",
              "birthDate": "2020-09-07",
              "type": { "id": 1, "name": "cat" }
            }
            """;

        this.mockMvc.perform(put("/api/owners/1/pets/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload))
            .andExpect(status().isNoContent());

        Pet updatedPet = clinicService.findPetById(1);
        assertThat(updatedPet.getName()).isEqualTo("LeoUpdated");
    }

    @Test
    @WithMockUser(roles = "OWNER_ADMIN")
    void addVisitToOwnerRejectsPetBelongingToDifferentOwner() throws Exception {
        int initialVisitCount = clinicService.findPetById(2).getVisits().size();

        String visitPayload = """
            {
              "date": "2026-05-10",
              "description": "Cross-owner injected visit"
            }
            """;

        this.mockMvc.perform(post("/api/owners/1/pets/2/visits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(visitPayload))
            .andExpect(status().isNotFound());

        assertThat(clinicService.findPetById(2).getVisits()).hasSize(initialVisitCount);
    }

    @Test
    @WithMockUser(roles = "OWNER_ADMIN")
    void addVisitToOwnerRejectsNonExistentOwner() throws Exception {
        int initialVisitCount = clinicService.findPetById(1).getVisits().size();

        String visitPayload = """
            {
              "date": "2026-05-10",
              "description": "Unknown owner visit"
            }
            """;

        this.mockMvc.perform(post("/api/owners/99999/pets/1/visits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(visitPayload))
            .andExpect(status().isNotFound());

        assertThat(clinicService.findPetById(1).getVisits()).hasSize(initialVisitCount);
    }

    @Test
    @WithMockUser(roles = "OWNER_ADMIN")
    void addVisitToOwnerReturnsNotFoundForNonExistentPet() throws Exception {
        String visitPayload = """
            {
              "date": "2026-05-10",
              "description": "Missing pet visit"
            }
            """;

        this.mockMvc.perform(post("/api/owners/1/pets/99999/visits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(visitPayload))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "OWNER_ADMIN")
    void addVisitToOwnerSucceedsForValidOwnerAndPet() throws Exception {
        String visitPayload = """
            {
              "date": "2026-05-10",
              "description": "Annual wellness checkup"
            }
            """;

        this.mockMvc.perform(post("/api/owners/1/pets/1/visits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(visitPayload))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.description").value("Annual wellness checkup"))
            .andExpect(jsonPath("$.petId").value(1));
    }
}
